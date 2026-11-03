package com.hihops.hops.ticket.service;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Component;

import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.inquiry.domain.InquiryProblem;
import com.hihops.hops.inquiry.domain.Message;
import com.hihops.hops.itsystem.repository.ItSystemRepository;
import com.hihops.hops.itsystem.service.ItSystemService;
import com.hihops.hops.ticket.domain.TicketWorkLog;
import com.hihops.hops.ticket.dto.ResolutionRequestResponse;
import com.hihops.hops.ticket.dto.WorkRecordResponse;
import com.hihops.hops.user.domain.Member;

import lombok.RequiredArgsConstructor;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 기존 AiRunRequest 계약에 맞춰 현재 문제와 원 기록만 실행 입력으로 고정한다. */
@Component
@RequiredArgsConstructor
public class TicketAgentInput {
    private final ObjectMapper mapper;
    private final ItSystemRepository systemRepository;
    private final ItSystemService systems;
    private final TicketStandardRequestReader standardRequests;

    private ObjectNode root(AgentRun run, Member actor, InquiryProblem problem, boolean curation) {
        var root = mapper.createObjectNode();
        root.put("contractVersion", "hops-system-1"); root.put("runId", run.getId().toString());
        root.put("kind", curation ? "CASE_DRAFT" : "INQUIRY_ANALYSIS");
        root.put("inquiryId", run.getInquiry().getId().toString()); root.put("inquiryVersion", run.getInputVersion());
        root.putArray("problemIds").add(problem.getId().toString());
        root.putArray("messages"); root.putArray("problems"); root.putArray("existingWorkItems"); root.putNull("workRecord");
        root.put("expiresAt", Instant.now().plusSeconds(60).toString());
        var limits = root.putObject("limits");
        limits.put("maxModelCalls", 8); limits.put("maxInputTokens", 60000); limits.put("maxOutputTokens", 1500);
        limits.put("maxToolCalls", 10); limits.put("maxRetries", 2);
        var context = root.putObject("accessContext");
        context.put("actorId", actor.getId().toString()); context.put("purpose", curation ? "CASE_DRAFT" : "EMPLOYEE_HELP");
        context.put("policyRevision", actor.getPolicyRevision());
        var scopes = context.putArray("allowedDocumentScopes"); scopes.add("EMPLOYEE"); if (curation) scopes.add("SYSTEM_STAFF");
        var available = curation ? List.of(run.getTicket().getResponsibleSystem()) : systemRepository.findByDeletedAtIsNullOrderByCodeAsc();
        context.set("systemIds", mapper.valueToTree(available.stream().map(value -> value.getId().toString()).toList()));
        var catalog = systems.responses(available);
        root.set("systems", mapper.valueToTree(available.stream().map(value -> catalog.get(value.getId())).toList()));
        return root;
    }

    public String curation(AgentRun run, TicketWorkLog record, ResolutionRequestResponse request, Member actor) {
        var ticket = record.getTicket(); var problem = ticket.getInquiryProblem();
        var root = root(run, actor, problem, true); root.putNull("answerMessageId");
        var standard = (ObjectNode) mapper.valueToTree(standardRequests.read(ticket.getStandardRequest(), actor));
        standard.remove(List.of("id", "problemId", "inquiryVersion"));
        var p = mapper.createObjectNode().put("id", problem.getId().toString()).put("summary", ticket.getTitle())
                .put("intent", ticket.getStandardRequest().getIntent().name()).put("processingType", ticket.getWorkType().name())
                .put("reportedSystemId", ticket.getStandardRequest().getReportedSystem().getId().toString())
                .put("state", "WAITING_CONFIRMATION").put("questionCount", problem.getClarifyCount());
        p.putNull("reportedFeatureId"); p.set("standardRequest", standard); p.putNull("resolutionContext");
        root.withArray("problems").add(p);
        var work = (ObjectNode) mapper.valueToTree(WorkRecordResponse.from(record));
        work.remove(List.of("author")); work.put("problemId", problem.getId().toString());
        work.put("systemId", ticket.getResponsibleSystem().getId().toString());
        work.put("resolutionRequestId", request.id().toString()); work.put("confirmationState", request.state());
        root.set("workRecord", work);
        root.withArray("existingWorkItems").add(mapper.createObjectNode().put("workItemId", problem.getId().toString())
                .put("problemId", problem.getId().toString()).put("inquiryVersion", run.getInputVersion())
                .put("outcome", "STORED").put("ticketId", ticket.getId().toString()));
        return mapper.writeValueAsString(root);
    }

    public String guide(AgentRun run, Message guide, InquiryProblem problem, ResolutionRequestResponse response, Member actor) {
        var root = root(run, actor, problem, false); root.put("answerMessageId", java.util.UUID.randomUUID().toString());
        var p = mapper.createObjectNode().put("id", problem.getId().toString()).put("summary", problem.getTitle())
                .put("intent", "HOW_TO").put("processingType", "KNOWLEDGE_QA").put("state", "ANALYZING")
                .put("questionCount", problem.getClarifyCount());
        p.putNull("reportedSystemId"); p.putNull("reportedFeatureId"); p.putNull("standardRequest");
        p.set("resolutionContext", mapper.valueToTree(java.util.Map.of("requestId", response.id(), "source", "GUIDE",
                "sourceId", guide.getId(), "sourceVersion", response.sourceVersion(), "state", "UNRESOLVED")));
        root.withArray("problems").add(p);
        root.withArray("messages").add(mapper.createObjectNode().put("id", guide.getId().toString())
                .put("revision", guide.getRevision()).put("sequenceNo", guide.getSequenceNo()).put("sender", "AI")
                .put("content", guide.getContent()).put("createdAt", guide.getCreatedAt().toString())
                .set("problemIds", mapper.valueToTree(List.of(problem.getId()))));
        return mapper.writeValueAsString(root);
    }
}
