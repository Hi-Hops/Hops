package com.hihops.hops.agent.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.agent.dto.AiRunRequest;
import com.hihops.hops.agent.dto.AiRunRequest.AccessContext;
import com.hihops.hops.agent.dto.AiRunRequest.ExistingWorkItem;
import com.hihops.hops.agent.dto.AiRunRequest.InputProblem;
import com.hihops.hops.agent.dto.AiRunRequest.StandardRequestBody;
import com.hihops.hops.itsystem.service.ItSystemService;
import com.hihops.hops.ticket.domain.TicketWorkLog;
import com.hihops.hops.ticket.dto.ResolutionRequestResponse;
import com.hihops.hops.ticket.dto.WorkRecordResponse;
import com.hihops.hops.ticket.service.TicketStandardRequestReader;
import com.hihops.hops.user.domain.Member;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/** 원 처리 기록을 불변 CASE_DRAFT 입력으로 고정한다. 전달은 공통 Dispatcher가 담당한다. */
@Component
@RequiredArgsConstructor
public class CurationInputFactory {
    private final AnalysisRunProperties properties;
    private final TicketStandardRequestReader standardRequests;
    private final ItSystemService systems;
    private final JsonMapper mapper;

    public record WorkRecord(UUID id, UUID ticketId, UUID problemId, UUID systemId, String kind,
            String action, String verification, String confirmedCause, String appliesToSystemVersion,
            String createdAt, UUID resolutionRequestId, String confirmationState) {}

    public String snapshot(AgentRun run, TicketWorkLog record, ResolutionRequestResponse confirmation,
            Member actor, Instant now) {
        var ticket = record.getTicket();
        var problem = ticket.getInquiryProblem();
        var system = ticket.getResponsibleSystem();
        var standard = standardRequests.read(ticket.getStandardRequest(), actor);
        var body = new StandardRequestBody(standard.summary(), standard.confirmedFacts(), standard.assumptions(),
                standard.unknowns(), standard.responsibleSystemCandidateId(), standard.responsibilityEvidenceIds(),
                standard.details(), standard.knowledge());
        var inputProblem = new InputProblem(problem.getId(), ticket.getTitle(), ticket.getStandardRequest().getIntent().name(),
                ticket.getWorkType().name(), ticket.getStandardRequest().getReportedSystem().getId(), null,
                "WAITING_CONFIRMATION", problem.getClarifyCount(), body, null);
        var work = new WorkRecord(record.getId(), ticket.getId(), problem.getId(), system.getId(),
                WorkRecordResponse.from(record).kind(), record.getActionDetail(), record.getVerificationResult(),
                record.getConfirmedCause(), record.getAppliedVersion(), AiRunRequest.time(record.getCreatedAt()),
                confirmation.id(), confirmation.state());
        var request = new AiRunRequest(properties.schemaVersion(), run.getId(), "CASE_DRAFT", ticket.getInquiry().getId(),
                run.getInputVersion(), null, List.of(problem.getId()), List.of(), List.of(inputProblem),
                List.of(systems.responses(List.of(system)).get(system.getId())),
                new AccessContext(actor.getId(), "CASE_DRAFT", List.of(system.getId()), List.of("SYSTEM_STAFF"), actor.getPolicyRevision()),
                List.of(new ExistingWorkItem(problem.getId(), problem.getId(), run.getInputVersion(), "STORED", ticket.getId())),
                work, AiRunRequest.time(now.plus(properties.deadline())), properties.limits());
        return mapper.writeValueAsString(request);
    }
}
