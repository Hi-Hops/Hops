package com.hihops.hops.agent.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;

import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.agent.domain.AgentTask;
import com.hihops.hops.agent.domain.RunStatus;
import com.hihops.hops.inquiry.repository.InquiryRepository;
import com.hihops.hops.itsystem.repository.ItSystemQueryRepository;
import com.hihops.hops.knowledge.domain.AccessLevel;
import com.hihops.hops.knowledge.domain.CaseOrigin;
import com.hihops.hops.knowledge.domain.CaseType;
import com.hihops.hops.knowledge.domain.ResolvedCase;
import com.hihops.hops.knowledge.repository.ResolvedCaseRepository;
import com.hihops.hops.ticket.domain.WorkLogType;
import com.hihops.hops.ticket.repository.TicketRepository;
import com.hihops.hops.user.repository.MemberRepository;
import com.hihops.hops.realtime.dto.TicketChangedEvent;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 공통 Dispatcher의 CURATION 결과 확장점. 업무 저장·실행 접수·전달을 중복 구현하지 않는다. */
@Service
@RequiredArgsConstructor
public class CurationResultHandler implements AgentRunResultHandler {
    private final MemberRepository members;
    private final ItSystemQueryRepository systems;
    private final InquiryRepository inquiries;
    private final TicketRepository tickets;
    private final ResolvedCaseRepository cases;
    private final EntityManager entities;
    private final JsonMapper mapper;
    private final ApplicationEventPublisher publisher;

    @Override
    public AgentTask task() { return AgentTask.CURATION; }

    @Override
    public void verifyAccess(AgentRun run, JsonNode input) {
        var ticket = run.getTicket();
        var work = run.getTriggerWorkLog();
        var context = input.path("accessContext");
        var actor = members.findWithDepartmentAndRolesByIdAndDeletedAtIsNull(
                UUID.fromString(context.path("actorId").asString())).orElseThrow(() -> rejected("AUTH_CONTEXT_INVALID"));
        if (ticket == null || work == null || ticket.isDeleted() || ticket.getInquiry().isDeleted()
                || ticket.getInquiryProblem().isDeleted() || actor.getDepartment().isDeleted()
                || actor.getPolicyRevision() != context.path("policyRevision").asInt(-1)
                || !ticket.getResponsibleSystem().getId().toString().equals(input.path("workRecord").path("systemId").asString())
                || !work.getId().toString().equals(input.path("workRecord").path("id").asString())
                || !work.getTicket().getId().equals(ticket.getId())
                || !systems.findActivePrimaryOwnerId(ticket.getResponsibleSystem().getId()).filter(actor.getId()::equals).isPresent()) {
            throw rejected("AUTH_CONTEXT_INVALID");
        }
    }

    @Override
    public String apply(AgentRun run, JsonNode input, JsonNode result) {
        // 정정·해결 응답과 같은 순서로 잠근 뒤 대기 중 바뀐 실행과 권한을 다시 읽는다.
        var inquiry = inquiries.findByIdAndDeletedAtIsNull(run.getInquiry().getId())
                .orElseThrow(() -> rejected("AUTH_CONTEXT_INVALID"));
        entities.refresh(inquiry, LockModeType.PESSIMISTIC_WRITE);
        var ticket = tickets.findForUpdate(run.getTicket().getId()).orElseThrow(() -> rejected("AUTH_CONTEXT_INVALID"));
        entities.refresh(ticket, LockModeType.PESSIMISTIC_WRITE);
        entities.refresh(run);
        if (run.getRunStatus() != RunStatus.RUNNING) throw rejected(AgentResultRejectedException.SUPERSEDED);
        verifyAccess(run, input);
        for (var field : List.of("problemProposals", "questions", "ticketProposals", "evidence")) requireEmpty(result.path(field));
        require(result.path("answer").isNull() && result.path("guide").isNull() && result.path("error").isNull());
        var proposal = result.path("caseProposal");
        require(proposal.path("reusable").isBoolean());
        requireEmpty(proposal.path("sourceMessageIds")); // CASE_DRAFT 입력에는 메시지 원문을 보내지 않는다.
        require(proposal.path("limitations").isArray());
        for (var limitation : proposal.path("limitations")) require(limitation.isString());
        require(proposal.path("entityLinks").isArray());
        for (var link : proposal.path("entityLinks")) {
            require(link.isObject() && "APPLIES_TO".equals(link.path("relation").asString())
                    && ticket.getResponsibleSystem().getId().toString().equals(link.path("targetId").asString()));
            requireEmpty(link.path("evidenceIds"));
        }
        if (!proposal.path("reusable").asBoolean()) {
            for (var field : List.of("title", "action", "verification", "applicability", "problemStatement", "accessScope", "appliesToSystemVersion")) {
                require(proposal.path(field).isNull());
            }
            requireEmpty(proposal.path("limitations")); requireEmpty(proposal.path("entityLinks"));
            publisher.publishEvent(new TicketChangedEvent(ticket.getId(), List.of(ticket.getResponsibleSystem().getId())));
            return mapper.writeValueAsString(Map.of("applied", true, "resources", List.of(), "caseGenerationState", "NO_REUSABLE_CASE"));
        }
        for (var field : List.of("title", "action", "verification", "applicability", "problemStatement")) {
            require(proposal.path(field).isString() && !proposal.path(field).asString().isBlank());
        }
        var title = proposal.path("title").asString();
        require(title.codePointCount(0, title.length()) <= 200);
        require(List.of("EMPLOYEE", "SYSTEM_STAFF").contains(proposal.path("accessScope").asString()));
        require(proposal.path("appliesToSystemVersion").isNull() || proposal.path("appliesToSystemVersion").isString());
        var work = run.getTriggerWorkLog();
        require((work.getLogType() == WorkLogType.RESOLVED || work.getLogType() == WorkLogType.IMPLEMENTED)
                && work.getVerificationResult() != null && !work.getVerificationResult().isBlank());
        var draft = new ResolvedCase("C-" + run.getId().toString().replace("-", "").substring(16), work,
                ticket.getResponsibleSystem(), null, work.getLogType() == WorkLogType.IMPLEMENTED ? CaseType.IMPLEMENTATION : CaseType.RESOLUTION,
                title, proposal.path("problemStatement").asString(), work.getActionDetail(), work.getVerificationResult(),
                "EMPLOYEE".equals(proposal.path("accessScope").asString()) ? AccessLevel.EMPLOYEE : AccessLevel.STAFF,
                CaseOrigin.SYNTHETIC, run);
        var metadata = mapper.createObjectNode();
        metadata.putArray("facts"); metadata.set("limitations", proposal.path("limitations"));
        draft.recordDraftMetadata(proposal.path("applicability").asString(), work.getConfirmedCause(), work.getAppliedVersion(),
                metadata.toString(), proposal.path("entityLinks").toString());
        cases.saveAndFlush(draft);
        publisher.publishEvent(new TicketChangedEvent(ticket.getId(), List.of(ticket.getResponsibleSystem().getId())));
        return mapper.writeValueAsString(Map.of("applied", true,
                "resources", List.of(Map.of("type", "CASE", "id", draft.getId())), "caseGenerationState", "SUCCEEDED"));
    }

    private static void requireEmpty(JsonNode value) { require(value.isArray() && value.isEmpty()); }
    private static void require(boolean valid) { if (!valid) throw rejected("INVALID_OUTPUT"); }
    private static AgentResultRejectedException rejected(String code) {
        return new AgentResultRejectedException(code, "사례 결과의 형식·출처 또는 현재 권한을 확인할 수 없습니다");
    }
}
