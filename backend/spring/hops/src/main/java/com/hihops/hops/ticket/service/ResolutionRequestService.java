package com.hihops.hops.ticket.service;

import java.time.Instant;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.agent.repository.AgentRunRepository;
import com.hihops.hops.agent.service.AgentRunQueued;
import com.hihops.hops.agent.service.GuideReanalysisInputFactory;
import com.hihops.hops.agent.service.AnalysisRunProperties;
import com.hihops.hops.agent.domain.RunStatus;
import com.hihops.hops.auth.service.AuthService;
import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.global.idempotency.ResourceRef;
import com.hihops.hops.global.idempotency.ResourceType;
import com.hihops.hops.global.idempotency.CommandResult;
import com.hihops.hops.global.idempotency.IdempotencyService;
import com.hihops.hops.global.idempotency.IdempotentRequest;
import com.hihops.hops.inquiry.domain.Message;
import com.hihops.hops.inquiry.domain.ProblemStatus;
import com.hihops.hops.inquiry.repository.InquiryProblemRepository;
import com.hihops.hops.inquiry.repository.InquiryRepository;
import com.hihops.hops.realtime.dto.TicketChangedEvent;
import com.hihops.hops.ticket.domain.ActorType;
import com.hihops.hops.ticket.domain.TicketEvent;
import com.hihops.hops.ticket.domain.TicketEventType;
import com.hihops.hops.ticket.domain.TicketStatus;
import com.hihops.hops.ticket.dto.ResolutionRequestResponse;
import com.hihops.hops.ticket.dto.ResolutionResponseCommand;
import com.hihops.hops.ticket.repository.TicketEventRepository;
import com.hihops.hops.ticket.repository.TicketRepository;
import com.hihops.hops.ticket.repository.TicketWorkLogRepository;

import lombok.RequiredArgsConstructor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 확인 요청은 기존 불변 티켓 이벤트 또는 안내 메시지 메타데이터에서 읽고 응답한다. */
@Service
@RequiredArgsConstructor
public class ResolutionRequestService {
    private final AuthService auth;
    private final TicketEventRepository events;
    private final TicketRepository tickets;
    private final TicketWorkLogRepository records;
    private final TicketProgressReader progress;
    private final InquiryRepository inquiries;
    private final InquiryProblemRepository problems;
    private final AgentRunRepository runs;
    private final TicketStoredData data;
    private final JsonMapper mapper;
    private final GuideReanalysisInputFactory inputs;
    private final AnalysisRunProperties properties;
    private final ResolutionRequestReader reader;
    private final IdempotencyService idempotency;
    private final Clock clock;
    private final ApplicationEventPublisher publisher;
    private final EntityManager entityManager;

    public ResolutionRequestResponse get(Jwt jwt, UUID id) {
        return reader.get(jwt, id);
    }

    public CommandResult respond(Jwt jwt, IdempotentRequest request, UUID id, ResolutionResponseCommand command) {
        reader.checkResponse(jwt, id);
        return idempotency.execute(request, operation -> respondOnce(jwt, id, command));
    }

    private ResourceRef respondOnce(Jwt jwt, UUID id, ResolutionResponseCommand command) {
        var initial = reader.stored(id);
        var inquiry = inquiries.findByIdAndDeletedAtIsNull(initial.request().inquiryId()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        entityManager.refresh(inquiry, LockModeType.PESSIMISTIC_WRITE);
        if (initial.ticket() != null) entityManager.refresh(initial.ticket(), LockModeType.PESSIMISTIC_WRITE);
        reader.checkResponse(jwt, id);
        var stored = reader.stored(id);
        var request = stored.request();
        if (!"PENDING".equals(request.state()) || command.expectedRequestRevision() != request.requestRevision()) {
            throw new BusinessException(ErrorCode.RESOLUTION_REVISION_CONFLICT);
        }
        boolean resolved = command.decision() == ResolutionResponseCommand.Decision.RESOLVED;
        Instant now = clock.instant();
        var response = new ResolutionRequestResponse(request.id(), request.inquiryId(), request.problemId(), request.ticketId(),
                request.source(), request.sourceId(), request.sourceVersion(), 2, command.decision().name(), request.resultSummary(),
                "HOPS_USER", request.createdAt(), now);
        if (stored.ticket() != null) {
            var ticket = stored.ticket();
            var current = progress.resolution(ticket, records.findByTicketIdOrderByCreatedAtAscIdAsc(ticket.getId()));
            if (ticket.getTicketStatus() != TicketStatus.WAITING_CONFIRM || current == null || !id.equals(current.id())) {
                throw new BusinessException(ErrorCode.RESOLUTION_REVISION_CONFLICT);
            }
            ticket.respondResolution(resolved, now);
            events.save(new TicketEvent(ticket, resolved ? TicketEventType.USER_CONFIRMED : TicketEventType.USER_NOT_RESOLVED,
                    ActorType.MEMBER, auth.currentMember(jwt), null, mapper.writeValueAsString(Map.of("resolutionRequest", request)),
                    mapper.writeValueAsString(Map.of("resolutionRequest", response)), null));
            publisher.publishEvent(new TicketChangedEvent(ticket.getId(), List.of(ticket.getResponsibleSystem().getId())));
        } else {
            reader.requireCurrentGuide(stored);
            var problem = problems.findById(request.problemId()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
            if (problem.getProblemStatus() != ProblemStatus.GUIDED) {
                throw new BusinessException(ErrorCode.RESOLUTION_REVISION_CONFLICT);
            }
            var payload = (ObjectNode) data.parse(stored.guide().getPayload());
            payload.set("resolutionRequest", mapper.valueToTree(response));
            stored.guide().recordResolution(mapper.writeValueAsString(payload));
            AgentRun run = null;
            if (!resolved) {
                var previous = inquiry.getCurrentRun();
                if (previous != null && (previous.getRunStatus() == RunStatus.QUEUED || previous.getRunStatus() == RunStatus.RUNNING)) {
                    previous.finish(RunStatus.CANCELLED, now);
                }
                run = runs.saveAndFlush(new AgentRun(inquiry, stored.guide(), inquiry.getVersion(), "{}",
                        properties.modelVersion(), properties.promptVersion(), properties.schemaVersion()));
                run.prepareInput(inputs.snapshot(run, inquiry, problem, response, now));
                publisher.publishEvent(new AgentRunQueued(run.getId()));
                inquiry.resumeAnalysis(run);
            }
            problem.respondGuide(resolved, now, run);
        }
        // 대상 문제만 변경하고, 문의 집계에서는 다른 문제의 상태를 그대로 유지한다.
        tickets.flush(); problems.flush();
        inquiry.updateResolution(problems.countUnresolved(inquiry.getId()) == 0);
        return new ResourceRef(ResourceType.RESOLUTION_REQUEST, id);
    }

}
