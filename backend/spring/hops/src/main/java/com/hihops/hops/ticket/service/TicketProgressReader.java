package com.hihops.hops.ticket.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.agent.domain.AgentTask;
import com.hihops.hops.agent.domain.RunStatus;
import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.global.exception.ErrorResponse;
import com.hihops.hops.global.filter.RequestIdFilter;
import com.hihops.hops.ticket.domain.Ticket;
import com.hihops.hops.ticket.domain.TicketWorkLog;
import com.hihops.hops.ticket.domain.WorkLogType;
import com.hihops.hops.ticket.dto.CaseGenerationResponse;
import com.hihops.hops.ticket.dto.ResolutionRequestResponse;
import com.hihops.hops.ticket.repository.TicketEventRepository;
import com.hihops.hops.ticket.repository.TicketProgressQueryRepository;

import lombok.RequiredArgsConstructor;

/** 처리·확인·사례 생성의 저장 결과를 읽는다. GET에서 실행·저장·성공 상태를 만들지 않는다. */
@Service
@RequiredArgsConstructor
public class TicketProgressReader {
    private final TicketEventRepository events;
    private final TicketProgressQueryRepository runs;
    private final TicketStoredData data;

    public ResolutionRequestResponse resolution(Ticket ticket, List<TicketWorkLog> records) {
        ResolutionRequestResponse result = null;
        for (var event : events.findByTicketIdOrderByCreatedAtAscIdAsc(ticket.getId())) {
            if (event.getAfterData() == null) continue;
            var node = data.parse(event.getAfterData()).get("resolutionRequest");
            if (node != null && !node.isNull()) result = data.value(node, ResolutionRequestResponse.class);
        }
        if (result == null) {
            if (ticket.getConfirmRequestedAt() != null) unavailable();
            return null;
        }
        UUID source = result.sourceId();
        TicketWorkLog record = records.stream().filter(value -> value.getId().equals(source)).findFirst().orElse(null);
        if (!ticket.getId().equals(result.ticketId()) || !ticket.getInquiry().getId().equals(result.inquiryId())
                || !ticket.getInquiryProblem().getId().equals(result.problemId()) || !"WORK_RECORD".equals(result.source())
                || result.sourceVersion() != 1 || record == null
                || (record.getLogType() != WorkLogType.RESOLVED && record.getLogType() != WorkLogType.IMPLEMENTED)) {
            unavailable();
        }
        return result;
    }

    public CaseGenerationResponse generation(Ticket ticket) {
        var curations = runs.findCurationsByTicketId(ticket.getId());
        if (curations.isEmpty()) return null;
        return generation(curations.getLast());
    }

    public UUID generationTicketId(UUID generationId) {
        return curation(generationId).getTicket().getId();
    }

    public CaseGenerationResponse generation(UUID generationId) {
        return generation(curation(generationId));
    }

    private AgentRun curation(UUID generationId) {
        return runs.findById(generationId).filter(run -> run.getTask() == AgentTask.CURATION && run.getTicket() != null
                && run.getTriggerWorkLog() != null).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private CaseGenerationResponse generation(AgentRun run) {
        var record = run.getTriggerWorkLog();
        if (!record.getTicket().getId().equals(run.getTicket().getId())
                || !run.getInquiry().getId().equals(run.getTicket().getInquiry().getId())
                || (record.getLogType() != WorkLogType.RESOLVED && record.getLogType() != WorkLogType.IMPLEMENTED)
                || record.getVerificationResult() == null || record.getVerificationResult().isBlank()) unavailable();
        String state;
        UUID caseId = null;
        ErrorResponse error = null;
        if (run.getRunStatus() == RunStatus.QUEUED) {
            state = "PENDING";
        } else if (run.getRunStatus() == RunStatus.RUNNING) {
            state = "RUNNING";
        } else if (run.getRunStatus() == RunStatus.SUCCEEDED) {
            var validation = run.getValidationResult() == null ? null : data.parse(run.getValidationResult());
            if (noReusableCase(run, validation)) {
                state = "NO_REUSABLE_CASE";
            } else {
                var resolved = runs.findCaseByWorkLogId(run.getTriggerWorkLog().getId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));
                if (resolved.getCuratorRun() == null || !resolved.getCuratorRun().getId().equals(run.getId())) unavailable();
                state = "SUCCEEDED";
                caseId = resolved.getId();
            }
        } else {
            state = "FAILED";
            error = ErrorResponse.of(ErrorCode.SERVICE_UNAVAILABLE, RequestIdFilter.currentRequestId());
        }
        return new CaseGenerationResponse(run.getId(), run.getTriggerWorkLog().getId(), state, caseId, error);
    }

    private boolean noReusableCase(AgentRun run, tools.jackson.databind.JsonNode validation) {
        if (validation == null) return false;
        if ("NO_REUSABLE_CASE".equals(validation.path("caseGenerationState").asString())) return true;
        // 별도 사례 저장 흐름은 적용 여부와 저장 자원 목록을 기록한다. 빈 자원만으로 성공을 추측하지 않는다.
        if (!validation.path("applied").isBoolean() || !validation.path("applied").asBoolean()
                || !validation.path("resources").isArray() || !validation.path("resources").isEmpty()
                || run.getResult() == null) return false;
        var reusable = data.parse(run.getResult()).path("caseProposal").path("reusable");
        return reusable.isBoolean() && !reusable.asBoolean();
    }

    private static void unavailable() {
        throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
    }
}
