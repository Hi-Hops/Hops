package com.hihops.hops.global.idempotency;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.hibernate.exception.ConstraintViolationException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.user.repository.MemberRepository;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/**
 * 같은 변경 요청을 한 번만 반영하고, 다시 오면 처음 결과를 돌려주는 곳 (WIRE_CONTRACT 12.1, docs/ERD.md 6.1절 idempotency_key).
 *
 * <p>왜 필요한가: 메시지를 보냈는데 응답이 네트워크에서 사라지면, 프론트는 입력을 보존하고 같은 Idempotency-Key로 결과를 다시 확인한다.
 * 서버가 이것을 새 요청으로 처리하면 같은 메시지·문의가 두 번 저장된다.
 *
 * <p>처리 순서 ({@link #execute}):
 * <ol>
 *   <li>같은 요청자·키의 기록이 있으면 업무를 다시 하지 않는다.
 *       메서드·경로·본문이 같으면 처음 결과를, 다르면 409 IDEMPOTENCY_KEY_REUSED를, 보존 기간이 지났으면 410을 돌려준다.</li>
 *   <li>처음 온 요청이면 한 트랜잭션 안에서 접수 기록(PENDING) → 업무 반영 → 최종 결과(SUCCEEDED)를 저장한다.
 *       업무가 예외를 던지면 기록도 함께 롤백된다.</li>
 *   <li>같은 키 요청이 동시에 오면 DB 중복 불가 제약이 뒤의 요청을 막는다. 뒤의 요청은 먼저 저장된 결과를 돌려준다.</li>
 * </ol>
 *
 * <p>사용법: Controller에서 트랜잭션 밖에서 부른다(트랜잭션 안에서 부르면 {@link IllegalStateException}).
 * 업무 코드는 넘기는 함수 안에서 실행되며 같은 트랜잭션에 참여한다.
 * commit 뒤에 할 일(AI 호출·SSE 알림)은 함수 안에서 {@code @TransactionalEventListener(AFTER_COMMIT)}용 이벤트로 발행한다.
 * <pre>
 * CommandResult result = idempotencyService.execute(request, operationId -&gt; {
 *     Inquiry inquiry = inquiryService.create(...);
 *     return new ResourceRef(ResourceType.INQUIRY, inquiry.getId());
 * });
 * return result.toResponseEntity();
 * </pre>
 *
 * <p>권한: 같은 키 재확인도 현재 권한 검사 뒤에 한다(WIRE_CONTRACT 12.1). 대상 권한 검사는 execute를 부르기 전에 끝낸다.
 *
 * <p>거절(4xx)은 아직 REJECTED로 기록하지 않는다. 업무와 함께 롤백되므로 같은 키로 다시 오면 다시 검사해 같은 오류를 돌려준다.
 * 후속: #26 "거절된 변경 요청을 REJECTED로 기록(REQUIRES_NEW)".
 */
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    /** 신규 프로필의 계약 이름. 이전 계약의 같은 키와 섞이지 않게 기록에 함께 남긴다. */
    public static final String CONTRACT_VERSION = "hops-system-1";

    /** 상세 결과 최소 보존 기간 (WIRE_CONTRACT 12.1 "최소 24시간") */
    static final Duration RESULT_RETENTION = Duration.ofHours(24);

    private static final String DUPLICATE_KEY_CONSTRAINT = "uk_idempotency_key_actor_contract_key";

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final MemberRepository memberRepository;
    private final TransactionTemplate transactionTemplate;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    /**
     * 변경 요청을 한 번만 반영한다.
     *
     * @param request 요청자·키·메서드·경로·본문
     * @param work    처음 온 요청일 때만 실행할 업무. 서버가 정한 operationId를 받아 저장한 업무 대상을 돌려준다
     * @return 처음 결과(200) 또는 접수·미확인 결과(202)
     * @throws IllegalStateException 이미 트랜잭션 안에서 불렀을 때
     */
    public CommandResult execute(IdempotentRequest request, Function<UUID, ResourceRef> work) {
        // 바깥 트랜잭션에 합류하면 같은 키 동시 요청의 유일 제약 위반이 그 트랜잭션을 abort시켜,
        // 아래 catch의 재조회가 "current transaction is aborted"로 실패한다. 실수로 감싸지 않게 바로 막는다.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("IdempotencyService.execute는 트랜잭션 밖에서 호출해야 한다");
        }
        String requestHash = hash(request.body());
        Optional<CommandResult> previous = findPrevious(request, requestHash);
        if (previous.isPresent()) {
            return previous.get();
        }
        try {
            return transactionTemplate.execute(status -> firstRun(request, requestHash, work));
        } catch (DataIntegrityViolationException e) {
            if (!isDuplicateKey(e)) {
                throw e; // 업무 쪽 제약 위반은 그대로 올린다
            }
            // 같은 키 요청이 동시에 들어와 다른 쪽이 먼저 저장했다. 그 결과를 돌려준다.
            log.info("같은 요청 키 동시 접수: 먼저 저장된 결과를 반환");
            return findPrevious(request, requestHash).orElseThrow(() -> e);
        }
    }

    /**
     * GET /operations/{operationId}: 요청자 본인의 작업 결과. 남의 작업이면 없는 것과 같이 404다.
     *
     * <p>권한은 요청자 본인 여부만 확인한다. 대상의 현재 권한은 다시 보지 않는데, 영수증에는 대상 종류와 ID만 있고
     * 내용은 대상 GET이 현재 권한으로 다시 검사하기 때문이다 (WIRE_CONTRACT 12.1 "권한 회수 뒤 비공개 결과 미반환").
     */
    @Transactional(readOnly = true)
    public CommandReceipt findByOperationId(UUID actorId, UUID operationId) {
        return idempotencyRecordRepository.findByOperationIdAndActor_Id(operationId, actorId)
                .map(this::receiptOf)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    /**
     * GET /operations/by-request/{requestId}: 요청 키로 작업 결과를 찾는다.
     * 404는 "요청이 도착하지 않았거나 확인할 수 없음"이다. 저장되지 않았다는 증거가 아니다 (WIRE_CONTRACT 12.1).
     */
    @Transactional(readOnly = true)
    public CommandReceipt findByRequestId(UUID actorId, UUID requestId) {
        return idempotencyRecordRepository
                .findByActor_IdAndContractVersionAndIdempotencyKey(actorId, CONTRACT_VERSION, requestId.toString())
                .map(this::receiptOf)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    /**
     * 이 업무 대상을 만든 변경 요청의 requestId. 예: 재시도로 만든 실행의 실패 오류에 그 재시도 요청 ID를 넣는다 (WIRE_CONTRACT 12.8 끝).
     * 보존 기간이 지나도 resource 표식은 남으므로 찾을 수 있다. 멱등 처리 없이 만든 대상이면 비어 있다.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> findRequestIdOf(ResourceRef resource) {
        return idempotencyRecordRepository.findFirstByResourceTypeAndResourceId(resource.type().name(), resource.id())
                .map(record -> UUID.fromString(record.getIdempotencyKey()));
    }

    private Optional<CommandResult> findPrevious(IdempotentRequest request, String requestHash) {
        return idempotencyRecordRepository
                .findByActor_IdAndContractVersionAndIdempotencyKey(request.actorId(), CONTRACT_VERSION,
                        request.requestId().toString())
                .map(record -> replay(record, request, requestHash));
    }

    private CommandResult firstRun(IdempotentRequest request, String requestHash, Function<UUID, ResourceRef> work) {
        UUID operationId = UUID.randomUUID();
        // 먼저 저장·flush해서, 같은 키 요청이 동시에 오면 업무를 실행하기 전에 중복 불가 제약으로 막는다
        IdempotencyRecord record = idempotencyRecordRepository.saveAndFlush(new IdempotencyRecord(
                memberRepository.getReferenceById(request.actorId()), CONTRACT_VERSION, request.httpMethod(),
                request.requestPath(), request.requestId().toString(), requestHash, operationId,
                OperationState.PENDING, clock.instant().plus(RESULT_RETENTION)));
        ResourceRef resource = work.apply(operationId);
        CommandReceipt receipt = CommandReceipt.succeeded(request.requestId(), operationId, resource);
        record.succeed(resource, HttpStatus.OK.value(), jsonMapper.writeValueAsString(receipt));
        return new CommandResult(HttpStatus.OK, receipt);
    }

    // 같은 키 재확인은 버전 비교보다 먼저 한다. 다른 의도면 409, 같은 의도면 처음 결과다.
    private CommandResult replay(IdempotencyRecord record, IdempotentRequest request, String requestHash) {
        boolean sameIntent = record.getHttpMethod().equals(request.httpMethod())
                && record.getRequestPath().equals(request.requestPath())
                && record.getRequestHash().equals(requestHash);
        if (!sameIntent) {
            log.info("같은 요청 키로 다른 요청 거절 operationId={}", record.getOperationId());
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        CommandReceipt receipt = receiptOf(record);
        HttpStatus status = receipt.state() == OperationState.SUCCEEDED
                ? HttpStatus.valueOf(record.getResponseStatus())
                : HttpStatus.ACCEPTED;
        log.info("같은 요청 재확인: 기존 결과 반환 operationId={} state={}", record.getOperationId(), receipt.state());
        return new CommandResult(status, receipt);
    }

    private CommandReceipt receiptOf(IdempotencyRecord record) {
        if (record.isExpired(clock.instant())) {
            throw new BusinessException(ErrorCode.OPERATION_RESULT_EXPIRED);
        }
        return switch (record.getOperationState()) {
            case SUCCEEDED -> jsonMapper.readValue(record.getResponseBody(), CommandReceipt.class);
            // 접수만 됐거나 결과를 확인하지 못함. 업무 대상이 기록돼 있으면 함께 알려준다.
            case PENDING, UNKNOWN -> new CommandReceipt(UUID.fromString(record.getIdempotencyKey()),
                    record.getOperationId(), record.getOperationState(), resourceOf(record), null);
            // 거절은 업무와 함께 롤백하므로 지금은 기록되지 않는다(클래스 설명). 기록하게 되면 저장한 오류를 담아 돌려준다(후속: #26).
            case REJECTED -> throw new IllegalStateException("REJECTED 기록은 아직 만들지 않는다: " + record.getOperationId());
        };
    }

    private static @Nullable ResourceRef resourceOf(IdempotencyRecord record) {
        return record.getResourceType() == null
                ? null
                : new ResourceRef(ResourceType.valueOf(record.getResourceType()), record.getResourceId());
    }

    // 본문을 JSON으로 다시 직렬화해 해시한다. 공백·줄바꿈 차이는 같은 요청으로, 값이 하나라도 다르면 다른 요청으로 본다.
    // 주의: 요청 DTO의 필드를 바꿔 배포하면, 배포 전 24시간 안의 같은 요청 재전송은 해시가 달라져 409가 된다.
    private String hash(Object body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(jsonMapper.writeValueAsBytes(body));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256은 모든 JDK가 제공한다", e);
        }
    }

    private static boolean isDuplicateKey(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && DUPLICATE_KEY_CONSTRAINT.equalsIgnoreCase(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
