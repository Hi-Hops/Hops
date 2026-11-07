package com.hihops.hops.inquiry.service;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.agent.service.AgentResultRejectedException;
import com.hihops.hops.inquiry.domain.Inquiry;
import com.hihops.hops.inquiry.domain.InquiryProblem;
import com.hihops.hops.inquiry.domain.ProblemStatus;
import com.hihops.hops.inquiry.domain.RequestIntent;
import com.hihops.hops.inquiry.domain.StandardRequest;
import com.hihops.hops.inquiry.domain.WorkType;
import com.hihops.hops.inquiry.repository.InquiryProblemRepository;
import com.hihops.hops.inquiry.repository.MessageRepository;
import com.hihops.hops.inquiry.repository.StandardRequestRepository;
import com.hihops.hops.itsystem.repository.ItSystemRepository;
import com.hihops.hops.ticket.dto.ResolutionRequestResponse;
import com.hihops.hops.ticket.dto.StandardRequestResponse.RequestDetailsResponse;
import com.hihops.hops.ticket.repository.TicketRepository;
import com.hihops.hops.ticket.service.TicketKnowledgeReader;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 문의 잠금·결과 반영 거래 안에서 안내 대상과 현재 공개 근거를 검증하고 확인 요청까지 함께 저장한다. */
@Component
@RequiredArgsConstructor
public class GuideResultWriter {
    private final InquiryProblemRepository problems;
    private final StandardRequestRepository requests;
    private final MessageRepository messages;
    private final ItSystemRepository systems;
    private final TicketRepository tickets;
    private final TicketKnowledgeReader knowledge;
    private final JsonMapper mapper;
    private final Clock clock;
    private final MessageProblemLinker linker;

    public record Applied(String problemRef, UUID problemId, int evidenceCount) { }

    public Applied apply(AgentRun run, Inquiry inquiry, UUID answerId, JsonNode input, JsonNode result) {
        var guide = result.path("guide");
        if (guide.isMissingNode() || guide.isNull()) return null;
        try {
            return save(run, inquiry, answerId, input, result, guide);
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private Applied save(AgentRun run, Inquiry inquiry, UUID answerId, JsonNode input, JsonNode result, JsonNode guide) {
        require(guide.isObject());
        String ref = text(guide.path("problemRef"));
        String body = text(guide.path("messageText"));
        String answer = text(result.path("answer"));
        int offset = answer.indexOf(body);
        require(offset >= 0);
        var proposals = result.path("problemProposals");
        require(proposals.isArray());
        var matching = java.util.stream.StreamSupport.stream(proposals.spliterator(), false)
                .filter(item -> ref.equals(item.path("problemRef").asString())).toList();
        require(matching.size() == 1);
        var proposal = matching.getFirst();
        for (String field : List.of("questions", "ticketProposals")) {
            for (var item : result.path(field)) require(!ref.equals(item.path("problemRef").asString()));
        }
        require("HOW_TO".equals(proposal.path("intent").asString())
                && "KNOWLEDGE_QA".equals(proposal.path("processingType").asString()));
        UUID systemId = uuid(proposal.path("reportedSystemCandidateId"));
        require(java.util.stream.StreamSupport.stream(input.path("systems").spliterator(), false)
                .anyMatch(system -> systemId.toString().equals(system.path("id").asString())));
        var system = systems.findForRead(systemId).orElseThrow(GuideResultWriter::invalid);

        Set<UUID> cited = ids(guide.path("evidenceIds"));
        require(!cited.isEmpty() && result.path("evidence").isArray());
        var selected = mapper.createArrayNode();
        Set<UUID> found = new HashSet<>();
        for (var evidence : result.path("evidence")) {
            UUID id = uuid(evidence.path("id"));
            if (cited.contains(id)) { require(found.add(id)); selected.add(evidence); }
        }
        require(found.equals(cited));
        var visible = knowledge.visible(selected.toString(), inquiry.getRequester());
        require(visible.size() == cited.size() && visible.stream().allMatch(item ->
                "EMPLOYEE".equals(item.accessScope()) && (item.systemId() == null || systemId.equals(item.systemId()))));

        Set<UUID> sourceIds = new HashSet<>();
        for (var message : input.path("messages")) {
            if ("USER".equals(message.path("sender").asString())) sourceIds.add(uuid(message.path("id")));
        }
        statements(proposal.path("facts"), sourceIds, cited, true);
        statements(proposal.path("assumptions"), sourceIds, cited, false);
        require(proposal.path("unknowns").isArray());
        proposal.path("unknowns").forEach(GuideResultWriter::text);
        require(proposal.path("details").isObject());
        require(mapper.treeToValue(proposal.path("details"), RequestDetailsResponse.class) != null);

        String summary = text(proposal.path("summary"));
        InquiryProblem problem;
        if (ref.matches("new:[A-Za-z0-9_-]+")) {
            String title = summary.substring(0, summary.offsetByCodePoints(0, Math.min(200, summary.codePointCount(0, summary.length()))));
            problem = problems.saveAndFlush(new InquiryProblem(inquiry, problems.findMaxProblemNo(inquiry.getId()) + 1,
                    title, null, ProblemStatus.GUIDED, run));
        } else {
            require(java.util.stream.StreamSupport.stream(input.path("problems").spliterator(), false)
                    .anyMatch(item -> ref.equals(item.path("id").asString())));
            require(input.path("problemIds").isEmpty() || java.util.stream.StreamSupport.stream(
                    input.path("problemIds").spliterator(), false).anyMatch(item -> ref.equals(item.asString())));
            problem = problems.findById(UUID.fromString(ref)).orElseThrow(GuideResultWriter::invalid);
            require(!problem.isDeleted() && problem.getInquiry().getId().equals(inquiry.getId())
                    && problem.getCreationOutcome() == null
                    && List.of(ProblemStatus.CLARIFYING, ProblemStatus.SYSTEM_PENDING, ProblemStatus.GUIDED).contains(problem.getProblemStatus())
                    && tickets.findByInquiryProblem_Id(problem.getId()).isEmpty());
        }
        var now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        problem.requestGuideConfirmation(run, now);
        var standard = new StandardRequest(problem, run.getInputVersion(), run, RequestIntent.HOW_TO,
                WorkType.KNOWLEDGE_QA, system, null, null, summary);
        standard.recordAnalysis(proposal.path("facts").toString(), proposal.path("assumptions").toString(),
                proposal.path("unknowns").toString(), proposal.path("details").toString(), mapper.writeValueAsString(visible));
        requests.saveAndFlush(standard);
        // 사실이 인용한 사용자 메시지를 이 문제에 잇는다. 연결이 없으면 미해결 뒤 티켓 도구의 출처 검증이 거부한다
        Set<UUID> cites = new HashSet<>();
        for (String field : List.of("facts", "assumptions")) {
            for (var statement : proposal.path(field)) cites.addAll(ids(statement.path("sourceMessageIds")));
        }
        for (UUID cite : cites) linker.link(inquiry, cite, problem.getId());

        var message = messages.findById(answerId).orElseThrow(GuideResultWriter::invalid);
        var payload = (tools.jackson.databind.node.ObjectNode) mapper.readTree(message.getPayload());
        var storedGuide = payload.putObject("guide").put("problemId", problem.getId().toString()).put("text", body);
        storedGuide.set("evidenceIds", guide.path("evidenceIds"));
        int start = answer.codePointCount(0, offset);
        payload.putArray("problemLinks").addObject().put("problemId", problem.getId().toString()).put("revision", 1)
                .putArray("spans").addObject().put("start", start).put("end", start + body.codePointCount(0, body.length()));
        var confirmation = new ResolutionRequestResponse(UUID.randomUUID(), inquiry.getId(), problem.getId(), null,
                "GUIDE", answerId, 1, 1, "PENDING", body, "HOPS_USER", now, null);
        payload.set("resolutionRequest", mapper.valueToTree(confirmation));
        message.recordResolution(payload.toString());
        return new Applied(ref, problem.getId(), cited.size());
    }

    static void statements(JsonNode values, Set<UUID> messages, Set<UUID> evidence, boolean confirmed) {
        require(values.isArray());
        for (var statement : values) {
            text(statement.path("text"));
            var sources = ids(statement.path("sourceMessageIds"));
            var knowledge = ids(statement.path("knowledgeEvidenceIds"));
            require(messages.containsAll(sources) && evidence.containsAll(knowledge)
                    && (!confirmed || !sources.isEmpty() || !knowledge.isEmpty()));
        }
    }

    static Set<UUID> ids(JsonNode values) {
        require(values.isArray());
        Set<UUID> result = new HashSet<>();
        for (var value : values) require(result.add(uuid(value)));
        return result;
    }

    static UUID uuid(JsonNode value) {
        String text = text(value);
        UUID id = UUID.fromString(text);
        require(id.toString().equals(text));
        return id;
    }

    static String text(JsonNode value) {
        require(value.isString() && !value.asString().isBlank());
        return value.asString();
    }

    static void require(boolean valid) { if (!valid) throw invalid(); }
    static AgentResultRejectedException invalid() {
        return new AgentResultRejectedException("INVALID_OUTPUT", "안내의 대상·출처 또는 현재 공개 범위를 확인할 수 없습니다");
    }
}
