package com.hihops.hops.agent.service;

import java.time.Clock;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.agent.dto.RunRetryCommand;
import com.hihops.hops.agent.repository.AgentRunRepository;
import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.global.idempotency.CommandResult;
import com.hihops.hops.global.idempotency.IdempotencyService;
import com.hihops.hops.global.idempotency.IdempotentRequest;
import com.hihops.hops.global.idempotency.ResourceRef;
import com.hihops.hops.global.idempotency.ResourceType;
import com.hihops.hops.inquiry.domain.Inquiry;
import com.hihops.hops.inquiry.domain.InquiryStatus;
import com.hihops.hops.inquiry.repository.InquiryRepository;
import com.hihops.hops.itsystem.service.SystemQueryService;

import lombok.RequiredArgsConstructor;

/**
 * 상담 실행 수동 재분석 (WIRE_CONTRACT 12.2 POST /runs/{runId}/retries, 12.8 8번).
 *
 * <p>문의의 현재 실행이 재분석 가능한 FAILED·TIMED_OUT일 때만, 같은 문의 버전으로 새 runId·새 answerMessageId의 QUEUED 실행을 접수한다.
 * 실패한 실행의 상태는 되돌리지 않고, 질문 횟수·저장된 업무도 그대로 둔다 (DATA_TYPES 실행 상태 흐름).
 * 응답은 200 CommandReceipt·resource=RUN(새 실행)이다. 같은 요청 키는 처음 결과를 돌려주고 새 실행을 만들지 않는다.
 *
 * <p>거절: 남의 실행·없는 실행은 404, 문의 버전이 다르면 409 INQUIRY_VERSION_CONFLICT,
 * 현재 실행이 아니거나 재분석할 수 없는 실패·끝난 문의면 409 RUN_NOT_RETRYABLE이다.
 * 같은 문의의 변경 명령은 문의 행 잠금으로 하나씩 처리하므로, 두 재분석이 동시에 와도 새 실행은 하나다.
 */
@Service
@RequiredArgsConstructor
public class RunCommandService {

    private final IdempotencyService idempotencyService;
    private final RunQueryService runQueryService;
    private final RunSummaryReader runSummaryReader;
    private final AgentRunRepository agentRunRepository;
    private final InquiryRepository inquiryRepository;
    private final SystemQueryService systemQueryService;
    private final AnalysisInputFactory analysisInputFactory;
    private final AnalysisRunProperties runProperties;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /** 트랜잭션 밖(Controller)에서 부른다. */
    public CommandResult retry(IdempotentRequest request, UUID runId, RunRetryCommand command) {
        // 같은 키 재확인도 현재 권한 검사 뒤에 한다 (WIRE_CONTRACT 12.1)
        runQueryService.findMyRun(request.actorId(), runId);
        return idempotencyService.execute(request,
                operationId -> new ResourceRef(ResourceType.RUN, retryRun(request.actorId(), runId, command)));
    }

    private UUID retryRun(UUID requesterId, UUID runId, RunRetryCommand command) {
        AgentRun failed = agentRunRepository.findById(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Inquiry inquiry = inquiryRepository.findByIdAndDeletedAtIsNull(failed.getInquiry().getId())
                .filter(found -> found.getRequester().getId().equals(requesterId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (inquiry.getVersion() != command.expectedInquiryVersion()) {
            throw new BusinessException(ErrorCode.INQUIRY_VERSION_CONFLICT);
        }
        boolean current = inquiry.getCurrentRun() != null && inquiry.getCurrentRun().getId().equals(runId);
        boolean open = inquiry.getInquiryStatus() != InquiryStatus.RESOLVED && inquiry.getInquiryStatus() != InquiryStatus.CLOSED;
        if (!current || !open || failed.getInputVersion() != inquiry.getVersion() || !runSummaryReader.isRetryable(failed)) {
            throw new BusinessException(ErrorCode.RUN_NOT_RETRYABLE);
        }

        AgentRun next = agentRunRepository.saveAndFlush(new AgentRun(inquiry, failed.getTriggerMessage(),
                inquiry.getVersion(), "{}", runProperties.modelVersion(), runProperties.promptVersion(),
                runProperties.schemaVersion()));
        next.prepareInput(analysisInputFactory.retrySnapshot(failed, next, inquiry,
                systemQueryService.findSupportedSystems(), clock.instant()));
        inquiry.resumeAnalysis(next);
        eventPublisher.publishEvent(new AgentRunQueued(next.getId()));
        return next.getId();
    }
}
