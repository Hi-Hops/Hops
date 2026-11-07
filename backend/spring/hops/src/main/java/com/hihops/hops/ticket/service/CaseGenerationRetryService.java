package com.hihops.hops.ticket.service;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.agent.domain.RunStatus;
import com.hihops.hops.agent.repository.AgentRunRepository;
import com.hihops.hops.agent.service.AgentRunQueued;
import com.hihops.hops.agent.service.AnalysisRunProperties;
import com.hihops.hops.agent.service.CurationInputFactory;
import com.hihops.hops.auth.service.AuthService;
import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.global.idempotency.CommandResult;
import com.hihops.hops.global.idempotency.IdempotencyService;
import com.hihops.hops.global.idempotency.IdempotentRequest;
import com.hihops.hops.global.idempotency.ResourceRef;
import com.hihops.hops.global.idempotency.ResourceType;
import com.hihops.hops.realtime.dto.TicketChangedEvent;
import com.hihops.hops.ticket.repository.TicketProgressQueryRepository;

import lombok.RequiredArgsConstructor;

/**
 * 실패한 사례 생성의 수동 재시도 (WIRE_CONTRACT 12.2 POST /case-generations/{generationId}/retries, 현재 담당자·FAILED).
 *
 * <p>같은 원 처리 기록에 새 Curator 실행을 접수한다. AI 서버는 같은 runId를 다시 받으면 이전 결과를 재생하거나 충돌로
 * 거절하므로 실행 id를 되살리지 않는다. 실패한 이전 실행은 그대로 남고 티켓의 현재 사례 생성은 가장 나중 실행이다.
 * 응답은 200 CommandReceipt·resource=CASE_GENERATION(새 실행)이며 같은 요청 키는 처음 결과를 돌려준다.
 *
 * <p>거절: 없는 생성·사례 생성이 아닌 실행은 404, 현재 담당자가 아니면 403, 현재 사례 생성이 아니거나(이미 다시 접수됨) 실패가
 * 아니거나 사례가 이미 있거나 문의자가 미해결로 응답했으면 409 INVALID_STATE. 현재 사례 생성은 티켓 전체의 마지막 실행이다. 같은 티켓의 변경은 티켓 행 잠금으로 하나씩 처리하므로 동시에 와도 새 실행은 하나다.
 */
@Service
@RequiredArgsConstructor
public class CaseGenerationRetryService {
    private static final List<RunStatus> RETRYABLE = List.of(RunStatus.FAILED, RunStatus.TIMED_OUT, RunStatus.CANCELLED);

    private final TicketCommandAccess access;
    private final TicketProgressReader progress;
    private final TicketProgressQueryRepository progressRuns;
    private final AgentRunRepository runs;
    private final AuthService auth;
    private final CurationInputFactory inputs;
    private final AnalysisRunProperties properties;
    private final IdempotencyService idempotency;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    /** 트랜잭션 밖(Controller)에서 부른다. */
    public CommandResult retry(Jwt jwt, IdempotentRequest request, UUID generationId) {
        // 같은 키 재확인도 현재 권한 검사 뒤에 한다 (WIRE_CONTRACT 12.1)
        access.check(jwt, progress.generationTicketId(generationId), false);
        return idempotency.execute(request,
                operationId -> new ResourceRef(ResourceType.CASE_GENERATION, retryRun(jwt, generationId)));
    }

    private UUID retryRun(Jwt jwt, UUID generationId) {
        UUID ticketId = progress.generationTicketId(generationId);
        var ticket = access.lock(jwt, ticketId, false);
        var actor = auth.currentMember(jwt);
        AgentRun failed = runs.findById(generationId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        var record = failed.getTriggerWorkLog();
        // 티켓 전체의 마지막 실행만 현재 사례 생성이다. 더 새로운 해결 기록이 있으면 이전 기록의 생성은 재시도하지 않는다.
        var curations = progressRuns.findCurationsByTicketId(ticketId);
        if (curations.isEmpty() || !curations.getLast().getId().equals(failed.getId())
                || !RETRYABLE.contains(failed.getRunStatus())
                || progressRuns.findCaseByWorkLogId(record.getId()).isPresent()) {
            throw new BusinessException(ErrorCode.INVALID_STATE);
        }
        var confirmation = progress.resolution(record);
        // 문의자가 해결되지 않았다고 답한 기록은 AI·Spring 모두 사례 없음으로 끝내므로 재시도해도 사례가 생기지 않는다.
        if ("UNRESOLVED".equals(confirmation.state())) throw new BusinessException(ErrorCode.INVALID_STATE);
        var now = clock.instant();
        var next = runs.saveAndFlush(new AgentRun(ticket.getInquiry(), ticket, record, ticket.getInquiry().getVersion(), "{}",
                properties.modelVersion(), properties.promptVersion(), properties.schemaVersion()));
        next.prepareInput(inputs.snapshot(next, record, confirmation, actor, now));
        publisher.publishEvent(new AgentRunQueued(next.getId()));
        publisher.publishEvent(new TicketChangedEvent(ticketId, List.of(ticket.getResponsibleSystem().getId())));
        return next.getId();
    }
}
