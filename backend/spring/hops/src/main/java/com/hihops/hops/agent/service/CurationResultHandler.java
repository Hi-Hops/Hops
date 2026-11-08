package com.hihops.hops.agent.service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.LinkedHashSet;
import java.util.Set;

import org.jspecify.annotations.Nullable;
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
import com.hihops.hops.relation.service.RelationLinkCandidateReader;
import com.hihops.hops.ticket.domain.WorkLogType;
import com.hihops.hops.ticket.repository.TicketRepository;
import com.hihops.hops.ticket.service.ResolutionRequestReader;
import com.hihops.hops.ticket.service.TicketKnowledgeReader;
import com.hihops.hops.ticket.dto.StandardRequestResponse.KnowledgeEvidenceResponse;
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
    private final TicketKnowledgeReader knowledge;
    private final RelationLinkCandidateReader relationLinks;

    /** 관계별 최대 연결 수. AI(curator-link)의 상한과 같고 APPLIES_TO는 시스템 하나다. */
    static final Map<String, Integer> MAX_LINKS = Map.of("APPLIES_TO", 1, "ADDRESSES", 3, "USES_CHECK", 5);

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
        for (var field : List.of("problemProposals", "questions", "ticketProposals")) requireEmpty(result.path(field));
        require(result.path("evidence").isArray());
        require(result.path("answer").isNull() && result.path("guide").isNull() && result.path("error").isNull());
        var confirmation = confirmations.stored(UUID.fromString(input.path("workRecord").path("resolutionRequestId").asString())).request();
        require("WORK_RECORD".equals(confirmation.source()) && ticket.getId().equals(confirmation.ticketId())
                && run.getTriggerWorkLog().getId().equals(confirmation.sourceId()));
        if ("UNRESOLVED".equals(confirmation.state())) {
            publisher.publishEvent(new TicketChangedEvent(ticket.getId(), List.of(ticket.getResponsibleSystem().getId())));
            return mapper.writeValueAsString(Map.of("applied", true, "resources", List.of(),
                    "caseGenerationState", "NO_REUSABLE_CASE", "reason", "USER_UNRESOLVED"));
        }
        var verifiedKnowledge = verifiedKnowledge(run, input, result.path("evidence"));
        Set<String> relationEvidence = new LinkedHashSet<>();
        verifiedKnowledge.forEach(evidence -> relationEvidence.add(evidence.id().toString()));
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
        relationEvidence.addAll(sources);
        verifyLinks(input, ticket, proposal.path("entityLinks"), relationEvidence);
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
        metadata.set("knowledge", mapper.valueToTree(verifiedKnowledge));
        draft.recordDraftMetadata(proposal.path("applicability").asString(), work.getConfirmedCause(), work.getAppliedVersion(),
                metadata.toString(), proposal.path("entityLinks").toString());
        cases.saveAndFlush(draft);
        publisher.publishEvent(new TicketChangedEvent(ticket.getId(), List.of(ticket.getResponsibleSystem().getId())));
        return mapper.writeValueAsString(Map.of("applied", true,
                "resources", List.of(Map.of("type", "CASE", "id", draft.getId())), "caseGenerationState", "SUCCEEDED"));
    }

    /**
     * 사례의 기존 노드 연결을 다시 확인한다. APPLIES_TO는 책임 시스템이다. ADDRESSES는 증상, USES_CHECK는 확인 항목이어야 하고
     * AI가 본 후보(입력의 linkCandidates)에 있으면서 지금도 승인되어 실행 주체에게 보이는 노드만 허용한다(새 노드·다른 시스템 금지).
     * 근거는 이 사례의 USER 출처, 입력에 고정한 이 문제의 메시지(AI 질문·답변 포함), 이 티켓의 처리 기록, 검증된 지식 근거 중 하나여야 한다.
     */
    private void verifyLinks(JsonNode input, com.hihops.hops.ticket.domain.Ticket ticket, JsonNode links, Set<String> relationEvidence) {
        UUID systemId = ticket.getResponsibleSystem().getId();
        Set<String> evidenceSpace = new LinkedHashSet<>(relationEvidence);
        UUID problemId = ticket.getInquiryProblem().getId();
        evidenceSpace.addAll(inputMessageIds(input, problemId, null));
        Set<String> aiMessageIds = inputMessageIds(input, problemId, "AI");
        var record = input.path("workRecord");
        if (record.path("id").isString()) evidenceSpace.add(record.path("id").asString());
        for (var related : record.path("relatedRecords")) {
            if (related.path("id").isString()) evidenceSpace.add(related.path("id").asString());
        }
        Map<String, String> offered = new java.util.HashMap<>();
        for (var candidate : record.path("linkCandidates")) {
            offered.put(candidate.path("id").asString(), candidate.path("kind").asString());
        }
        Set<String> linkable = new LinkedHashSet<>();
        boolean hasGraphLinks = links.valueStream()
                .map(link -> link.path("relation").asString())
                .anyMatch(relation -> "ADDRESSES".equals(relation) || "USES_CHECK".equals(relation));
        if (hasGraphLinks && !offered.isEmpty()) {
            var actor = UUID.fromString(input.path("accessContext").path("actorId").asString());
            for (var current : relationLinks.currentlyLinkable(systemId, actor)) {
                if (current.kind().equals(offered.get(current.id().toString()))) {
                    linkable.add(current.id().toString());
                }
            }
        }
        Set<String> seen = new LinkedHashSet<>();
        Map<String, Integer> counts = new java.util.HashMap<>();
        for (var link : links) {
            require(link.isObject());
            String relation = link.path("relation").asString();
            String target = link.path("targetId").asString();
            switch (relation) {
                case "APPLIES_TO" -> require(systemId.toString().equals(target));
                case "ADDRESSES" -> require("SYMPTOM".equals(offered.get(target)) && linkable.contains(target));
                case "USES_CHECK" -> require("CHECK_ITEM".equals(offered.get(target)) && linkable.contains(target));
                default -> throw rejected("INVALID_OUTPUT");
            }
            require(seen.add(relation + ":" + target));
            require(counts.merge(relation, 1, Integer::sum) <= MAX_LINKS.get(relation));
            var evidence = sourceIds(link.path("evidenceIds"));
            require(!evidence.isEmpty() && evidenceSpace.containsAll(evidence));
            // 확인했다는 연결(USES_CHECK)은 AI가 물은 메시지로 인정하지 않는다. 처리 기록·사용자 메시지만 근거다.
            if ("USES_CHECK".equals(relation)) require(Collections.disjoint(evidence, aiMessageIds));
        }
    }

    /** 입력에 고정한 이 문제의 메시지 ID(sender가 null이면 모두). 관계 근거로만 쓰며 사례 출처(allowedSourceIds)와 다르다. */
    private static Set<String> inputMessageIds(JsonNode input, UUID problemId, @Nullable String sender) {
        Set<String> ids = new LinkedHashSet<>();
        for (var message : input.path("messages")) {
            var problems = message.path("problemIds");
            if (problems.isArray() && problems.size() == 1 && problemId.toString().equals(problems.get(0).asString())
                    && message.path("id").isString()
                    && (sender == null || sender.equals(message.path("sender").asString()))) {
                ids.add(message.path("id").asString());
            }
        }
        return ids;
    }

    private List<KnowledgeEvidenceResponse> verifiedKnowledge(AgentRun run, JsonNode input, JsonNode evidence) {
        var ids = mapper.createArrayNode();
        for (var item : evidence) {
            require(item.isObject());
            ids.add(item.path("id"));
            try {
                require(mapper.treeToValue(item, KnowledgeEvidenceResponse.class) != null);
            } catch (RuntimeException exception) {
                throw rejected("INVALID_OUTPUT");
            }
        }
        var unique = sourceIds(ids);
        var actor = members.findWithDepartmentAndRolesByIdAndDeletedAtIsNull(
                UUID.fromString(input.path("accessContext").path("actorId").asString()))
                .orElseThrow(() -> rejected("AUTH_CONTEXT_INVALID"));
        var visible = knowledge.visible(evidence.toString(), actor);
        require(visible.size() == unique.size());
        Set<String> scopes = new LinkedHashSet<>();
        input.path("accessContext").path("allowedDocumentScopes").forEach(scope -> scopes.add(scope.asString()));
        for (var item : visible) {
            require(scopes.contains(item.accessScope()) && (run.getTicket().getResponsibleSystem().getId().equals(item.systemId())
                    || item.systemId() == null && "POLICY".equals(item.kind())));
        }
        return visible;
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
