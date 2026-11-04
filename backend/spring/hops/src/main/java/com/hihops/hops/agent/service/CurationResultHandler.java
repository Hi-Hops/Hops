package com.hihops.hops.agent.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.LinkedHashSet;
import java.util.Set;

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
import com.hihops.hops.ticket.service.ResolutionRequestReader;
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
    private final ResolutionRequestReader confirmations;

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
        var confirmation = confirmations.stored(UUID.fromString(input.path("workRecord").path("resolutionRequestId").asString())).request();
        require("WORK_RECORD".equals(confirmation.source()) && ticket.getId().equals(confirmation.ticketId())
                && run.getTriggerWorkLog().getId().equals(confirmation.sourceId()));
        if ("UNRESOLVED".equals(confirmation.state())) {
            publisher.publishEvent(new TicketChangedEvent(ticket.getId(), List.of(ticket.getResponsibleSystem().getId())));
            return mapper.writeValueAsString(Map.of("applied", true, "resources", List.of(),
                    "caseGenerationState", "NO_REUSABLE_CASE", "reason", "USER_UNRESOLVED"));
        }
        var proposal = result.path("caseProposal");
        require(proposal.path("reusable").isBoolean());
        require(proposal.path("limitations").isArray());
        for (var limitation : proposal.path("limitations")) require(limitation.isString());
        require(proposal.path("entityLinks").isArray());
        if (!proposal.path("reusable").asBoolean()) {
            for (var field : List.of("title", "action", "verification", "applicability", "problemStatement", "accessScope", "appliesToSystemVersion")) {
                require(proposal.path(field).isNull());
            }
            requireEmpty(proposal.path("limitations")); requireEmpty(proposal.path("entityLinks"));
            requireEmpty(proposal.path("sourceMessageIds"));
            publisher.publishEvent(new TicketChangedEvent(ticket.getId(), List.of(ticket.getResponsibleSystem().getId())));
            return mapper.writeValueAsString(Map.of("applied", true, "resources", List.of(), "caseGenerationState", "NO_REUSABLE_CASE"));
        }
        var sources = sourceIds(proposal.path("sourceMessageIds"));
        require(!sources.isEmpty() && allowedSourceIds(input, ticket.getInquiryProblem().getId()).containsAll(sources));
        for (var link : proposal.path("entityLinks")) {
            require(link.isObject() && "APPLIES_TO".equals(link.path("relation").asString())
                    && ticket.getResponsibleSystem().getId().toString().equals(link.path("targetId").asString()));
            var evidence = sourceIds(link.path("evidenceIds"));
            require(!evidence.isEmpty() && sources.containsAll(evidence));
        }
        for (var field : List.of("title", "action", "verification", "applicability", "problemStatement")) {
            require(proposal.path(field).isString() && !proposal.path(field).asString().isBlank());
        }
        var title = proposal.path("title").asString();
        require(title.codePointCount(0, title.length()) <= 200);
        require("SYSTEM_STAFF".equals(proposal.path("accessScope").asString()));
        require(proposal.path("appliesToSystemVersion").isNull() || proposal.path("appliesToSystemVersion").isString());
        var work = run.getTriggerWorkLog();
        require((work.getLogType() == WorkLogType.RESOLVED || work.getLogType() == WorkLogType.IMPLEMENTED)
                && work.getVerificationResult() != null && !work.getVerificationResult().isBlank());
        var draft = new ResolvedCase("C-" + run.getId().toString().replace("-", "").substring(16), work,
                ticket.getResponsibleSystem(), null, work.getLogType() == WorkLogType.IMPLEMENTED ? CaseType.IMPLEMENTATION : CaseType.RESOLUTION,
                title, proposal.path("problemStatement").asString(), work.getActionDetail(), work.getVerificationResult(),
                AccessLevel.STAFF,
                CaseOrigin.SYNTHETIC, run);
        var metadata = mapper.createObjectNode();
        metadata.putArray("facts"); metadata.set("limitations", proposal.path("limitations"));
        metadata.set("sourceMessageIds", proposal.path("sourceMessageIds"));
        draft.recordDraftMetadata(proposal.path("applicability").asString(), work.getConfirmedCause(), work.getAppliedVersion(),
                metadata.toString(), proposal.path("entityLinks").toString());
        cases.saveAndFlush(draft);
        publisher.publishEvent(new TicketChangedEvent(ticket.getId(), List.of(ticket.getResponsibleSystem().getId())));
        return mapper.writeValueAsString(Map.of("applied", true,
                "resources", List.of(Map.of("type", "CASE", "id", draft.getId())), "caseGenerationState", "SUCCEEDED"));
    }

    /** 입력에 실제로 고정한 해당 문제의 USER 원문만 사례 출처로 허용한다. */
    private static Set<String> allowedSourceIds(JsonNode input, UUID problemId) {
        Set<String> allowed = new LinkedHashSet<>();
        for (var message : input.path("messages")) {
            var problems = message.path("problemIds");
            if ("USER".equals(message.path("sender").asString()) && problems.isArray() && problems.size() == 1
                    && problemId.toString().equals(problems.get(0).asString())
                    && message.path("content").isString() && !message.path("content").asString().isBlank()) {
                allowed.add(message.path("id").asString());
            }
        }
        return allowed;
    }

    private static Set<String> sourceIds(JsonNode values) {
        require(values.isArray());
        Set<String> ids = new LinkedHashSet<>();
        for (var value : values) {
            require(value.isString());
            String id = value.asString();
            try {
                require(UUID.fromString(id).toString().equals(id));
            } catch (IllegalArgumentException exception) {
                throw rejected("INVALID_OUTPUT");
            }
            require(ids.add(id));
        }
        return ids;
    }

    private static void requireEmpty(JsonNode value) { require(value.isArray() && value.isEmpty()); }
    private static void require(boolean valid) { if (!valid) throw rejected("INVALID_OUTPUT"); }
    private static AgentResultRejectedException rejected(String code) {
        return new AgentResultRejectedException(code, "사례 결과의 형식·출처 또는 현재 권한을 확인할 수 없습니다");
    }
}
