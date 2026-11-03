package com.hihops.hops.global.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;

/**
 * 같은 변경 요청을 한 번만 반영하는지 실제 테스트 DB(hops_test)로 확인한다 (WIRE_CONTRACT 12.1).
 *
 * <p>서비스가 직접 트랜잭션을 열고, 동시 요청은 실제 commit이 있어야 DB 중복 불가 제약이 동작한다.
 * 그래서 테스트 전체를 롤백하는 {@code @Transactional}을 쓰지 않고, 넣은 행을 {@link #cleanup()}에서 지운다.
 *
 * <p>업무 대신 테스트 전용 test_sample 테이블에 행을 넣어 "업무가 몇 번 실행됐는지"를 센다.
 */
@SpringBootTest
@ActiveProfiles({"db", "test"})
class IdempotencyServiceTest {

    private static final String PATH = "/api/v2/inquiries";

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private final UUID departmentId = UUID.randomUUID();
    private final UUID memberId = UUID.randomUUID();
    private final UUID otherMemberId = UUID.randomUUID();
    // 이 테스트가 넣은 test_sample 행만 세고 지우려고 이름에 붙인다
    private final String marker = "idem-" + UUID.randomUUID().toString().substring(0, 8);

    record SampleCommand(UUID requestId, String content) {
    }

    @BeforeEach
    void createFixtures() {
        jdbc.update("INSERT INTO department (id, code, name) VALUES (?, ?, '중복 요청 테스트 부서')",
                departmentId, "IDEM_" + departmentId.toString().substring(0, 20));
        for (UUID id : List.of(memberId, otherMemberId)) {
            jdbc.update("INSERT INTO member (id, login_id, employee_no, password_hash, name, department_id) "
                    + "VALUES (?, ?, ?, 'unused-test-hash', '중복 요청 테스트 회원', ?)",
                    id, id.toString(), id.toString(), departmentId);
        }
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM test_sample WHERE name = ?", marker);
        jdbc.update("DELETE FROM idempotency_key WHERE actor_member_id IN (?, ?)", memberId, otherMemberId);
        jdbc.update("DELETE FROM member WHERE id IN (?, ?)", memberId, otherMemberId);
        jdbc.update("DELETE FROM department WHERE id = ?", departmentId);
    }

    @Test
    void 처음_온_요청은_업무를_실행하고_200_영수증을_기록한다() {
        UUID requestId = UUID.randomUUID();

        CommandResult result = idempotencyService.execute(request(memberId, requestId, PATH, "첫 문의"), this::work);

        assertThat(result.status()).isEqualTo(HttpStatus.OK);
        CommandReceipt receipt = result.receipt();
        assertThat(receipt.requestId()).isEqualTo(requestId);
        assertThat(receipt.state()).isEqualTo(OperationState.SUCCEEDED);
        assertThat(receipt.resource().type()).isEqualTo(ResourceType.INQUIRY);
        assertThat(receipt.error()).isNull();
        assertThat(businessRows()).isEqualTo(1);
        // 업무와 같은 트랜잭션에 최종 결과까지 기록됐다
        assertThat(jdbc.queryForObject("SELECT operation_state FROM idempotency_key WHERE operation_id = ?",
                String.class, receipt.operationId())).isEqualTo("SUCCEEDED");
    }

    @Test
    void 같은_키와_같은_본문은_업무를_다시_실행하지_않고_처음_결과를_돌려준다() {
        UUID requestId = UUID.randomUUID();
        CommandResult first = idempotencyService.execute(request(memberId, requestId, PATH, "응답이 유실된 문의"), this::work);

        // 응답을 받지 못한 프론트가 같은 키·같은 본문으로 다시 보낸다. 경로 대소문자·끝 / 차이는 같은 요청이다.
        CommandResult again = idempotencyService.execute(
                request(memberId, requestId, "/API/v2/inquiries/", "응답이 유실된 문의"), this::work);

        assertThat(again.status()).isEqualTo(HttpStatus.OK);
        assertThat(again.receipt()).isEqualTo(first.receipt());
        assertThat(businessRows()).isEqualTo(1);
    }

    @Test
    void 같은_키로_다른_본문이나_다른_경로를_보내면_409로_거절한다() {
        UUID requestId = UUID.randomUUID();
        idempotencyService.execute(request(memberId, requestId, PATH, "원래 내용"), this::work);

        assertErrorCode(() -> idempotencyService.execute(request(memberId, requestId, PATH, "바꾼 내용"), this::work),
                ErrorCode.IDEMPOTENCY_KEY_REUSED);
        assertErrorCode(() -> idempotencyService.execute(
                request(memberId, requestId, PATH + "/" + UUID.randomUUID() + "/messages", "원래 내용"), this::work),
                ErrorCode.IDEMPOTENCY_KEY_REUSED);
        assertErrorCode(() -> idempotencyService.execute(request(memberId, requestId, "PUT", PATH, "원래 내용"),
                this::work), ErrorCode.IDEMPOTENCY_KEY_REUSED);
        assertThat(businessRows()).isEqualTo(1);
    }

    @Test
    void 트랜잭션_안에서_부르면_업무를_실행하지_않고_바로_거절한다() {
        IdempotentRequest request = request(memberId, UUID.randomUUID(), PATH, "트랜잭션 안의 호출");

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(
                status -> idempotencyService.execute(request, this::work)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(businessRows()).isZero();
        assertThat(recordCount(memberId)).isZero();
    }

    @Test
    void 접수만_된_요청을_다시_보내면_202와_PENDING_영수증을_돌려준다() {
        UUID requestId = UUID.randomUUID();
        CommandResult first = idempotencyService.execute(request(memberId, requestId, PATH, "접수 중인 요청"), this::work);
        // 업무 반영 전에 commit된 접수 기록을 흉내 낸다 (지금 코드는 만들지 않지만 계약상 가능한 상태)
        jdbc.update("UPDATE idempotency_key SET operation_state = 'PENDING', resource_type = NULL, resource_id = NULL, "
                + "response_status = NULL, response_body = NULL WHERE operation_id = ?", first.receipt().operationId());

        CommandResult again = idempotencyService.execute(request(memberId, requestId, PATH, "접수 중인 요청"), this::work);

        assertThat(again.status()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(again.receipt()).isEqualTo(new CommandReceipt(requestId, first.receipt().operationId(),
                OperationState.PENDING, null, null));
        assertThat(businessRows()).isEqualTo(1);
    }

    @Test
    void 업무가_거절되면_기록도_롤백되어_같은_키로_다시_처리할_수_있다() {
        UUID requestId = UUID.randomUUID();
        IdempotentRequest request = request(memberId, requestId, PATH, "버전이 맞지 않는 문의");

        assertErrorCode(() -> idempotencyService.execute(request, operationId -> {
            work(operationId);
            throw new BusinessException(ErrorCode.CONFLICT);
        }), ErrorCode.CONFLICT);
        assertThat(businessRows()).isZero();
        assertThat(recordCount(memberId)).isZero();

        CommandResult retried = idempotencyService.execute(request, this::work);
        assertThat(retried.receipt().state()).isEqualTo(OperationState.SUCCEEDED);
        assertThat(businessRows()).isEqualTo(1);
    }

    @Test
    void 결과_보존_기간이_지난_키는_410으로_응답하고_업무를_다시_실행하지_않는다() {
        UUID requestId = UUID.randomUUID();
        CommandResult first = idempotencyService.execute(request(memberId, requestId, PATH, "오래된 요청"), this::work);
        jdbc.update("UPDATE idempotency_key SET expires_at = now() - interval '1 second' WHERE operation_id = ?",
                first.receipt().operationId());

        assertErrorCode(() -> idempotencyService.execute(request(memberId, requestId, PATH, "오래된 요청"), this::work),
                ErrorCode.OPERATION_RESULT_EXPIRED);
        assertErrorCode(() -> idempotencyService.findByRequestId(memberId, requestId),
                ErrorCode.OPERATION_RESULT_EXPIRED);
        assertThat(businessRows()).isEqualTo(1);
    }

    @Test
    void 같은_키_요청이_동시에_와도_업무는_한_번만_실행한다() throws Exception {
        UUID requestId = UUID.randomUUID();
        IdempotentRequest request = request(memberId, requestId, PATH, "두 번 누른 전송 버튼");
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<CommandResult>> futures = List.of(
                    pool.submit(() -> concurrentRun(start, request, executions)),
                    pool.submit(() -> concurrentRun(start, request, executions)));
            start.countDown();

            CommandResult a = futures.get(0).get(10, TimeUnit.SECONDS);
            CommandResult b = futures.get(1).get(10, TimeUnit.SECONDS);
            assertThat(a.receipt()).isEqualTo(b.receipt());
            assertThat(executions).hasValue(1);
            assertThat(businessRows()).isEqualTo(1);
            assertThat(recordCount(memberId)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 작업_결과는_요청자_본인만_조회하고_다른_사람의_같은_키와_섞이지_않는다() {
        UUID requestId = UUID.randomUUID();
        CommandResult mine = idempotencyService.execute(request(memberId, requestId, PATH, "내 문의"), this::work);

        assertThat(idempotencyService.findByOperationId(memberId, mine.receipt().operationId()))
                .isEqualTo(mine.receipt());
        assertThat(idempotencyService.findByRequestId(memberId, requestId)).isEqualTo(mine.receipt());
        assertErrorCode(() -> idempotencyService.findByOperationId(otherMemberId, mine.receipt().operationId()),
                ErrorCode.NOT_FOUND);
        assertErrorCode(() -> idempotencyService.findByRequestId(otherMemberId, requestId), ErrorCode.NOT_FOUND);

        // 다른 사람이 우연히 같은 키를 써도 자기 요청으로 따로 처리된다
        CommandResult others = idempotencyService.execute(request(otherMemberId, requestId, PATH, "남의 문의"), this::work);
        assertThat(others.receipt().operationId()).isNotEqualTo(mine.receipt().operationId());
        assertThat(businessRows()).isEqualTo(2);
    }

    private CommandResult concurrentRun(CountDownLatch start, IdempotentRequest request, AtomicInteger executions)
            throws InterruptedException {
        start.await();
        return idempotencyService.execute(request, operationId -> {
            executions.incrementAndGet();
            sleep(300); // 다른 요청이 같은 키로 끼어들 시간을 준다
            return work(operationId);
        });
    }

    private ResourceRef work(UUID operationId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO test_sample (id, name) VALUES (?, ?)", id, marker);
        return new ResourceRef(ResourceType.INQUIRY, id);
    }

    private IdempotentRequest request(UUID actorId, UUID requestId, String path, String content) {
        return request(actorId, requestId, "POST", path, content);
    }

    private IdempotentRequest request(UUID actorId, UUID requestId, String method, String path, String content) {
        return new IdempotentRequest(actorId, requestId, method, IdempotentRequest.normalizePath(path),
                new SampleCommand(requestId, content));
    }

    private int businessRows() {
        return jdbc.queryForObject("SELECT count(*) FROM test_sample WHERE name = ?", Integer.class, marker);
    }

    private int recordCount(UUID actorId) {
        return jdbc.queryForObject("SELECT count(*) FROM idempotency_key WHERE actor_member_id = ?", Integer.class,
                actorId);
    }

    private static void assertErrorCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(expected));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
