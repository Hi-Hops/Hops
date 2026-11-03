package com.hihops.hops.ticket.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.agent.repository.AgentRunRepository;
import com.hihops.hops.agent.service.AgentRunQueued;
import com.hihops.hops.auth.service.AuthService;
import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.global.idempotency.ResourceRef;
import com.hihops.hops.global.idempotency.ResourceType;
import com.hihops.hops.inquiry.domain.Message;
import com.hihops.hops.inquiry.domain.ProblemStatus;
import com.hihops.hops.inquiry.domain.SenderType;
import com.hihops.hops.inquiry.repository.InquiryProblemRepository;
import com.hihops.hops.inquiry.repository.InquiryRepository;
import com.hihops.hops.inquiry.repository.MessageRepository;
import com.hihops.hops.realtime.dto.TicketChangedEvent;
import com.hihops.hops.ticket.domain.ActorType;
import com.hihops.hops.ticket.domain.Ticket;
import com.hihops.hops.ticket.domain.TicketEvent;
import com.hihops.hops.ticket.domain.TicketEventType;
import com.hihops.hops.ticket.domain.TicketStatus;
import com.hihops.hops.ticket.domain.WorkLogType;
import com.hihops.hops.ticket.dto.ResolutionRequestResponse;
import com.hihops.hops.ticket.dto.ResolutionResponseCommand;
import com.hihops.hops.ticket.repository.TicketEventRepository;
import com.hihops.hops.ticket.repository.TicketRepository;
import com.hihops.hops.ticket.repository.TicketWorkLogRepository;

import lombok.RequiredArgsConstructor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 확인 요청은 기존 불변 티켓 이벤트 또는 안내 메시지 메타데이터에서 읽고 응답한다. */
@Service
@RequiredArgsConstructor
public class ResolutionRequestService {
    private final AuthService auth;
    private final TicketAuthorization authorization;
    private final TicketEventRepository events;
    private final TicketRepository tickets;
    private final TicketWorkLogRepository records;
    private final TicketProgressReader progress;
    private final MessageRepository messages;
    private final InquiryRepository inquiries;
    private final InquiryProblemRepository problems;
    private final AgentRunRepository runs;
    private final TicketStoredData data;
    private final ObjectMapper mapper;
    private final TicketAgentInput inputs;
    private final ApplicationEventPublisher publisher;
    private final EntityManager entityManager;

    private record Stored(ResolutionRequestResponse request, Ticket ticket, Message guide) { }

    private Stored stored(UUID id) {
        var event = events.findResolution(id);
        if (event.isPresent()) {
            var ticket = event.get().getTicket();
            if (ticket.isDeleted() || ticket.getInquiry().isDeleted() || ticket.getInquiryProblem().isDeleted()) notFound();
            var request = data.value(data.parse(event.get().getAfterData()).path("resolutionRequest"), ResolutionRequestResponse.class);
            var record = records.findById(request.sourceId()).orElseThrow(() -> new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));
            if (!"WORK_RECORD".equals(request.source()) || request.sourceVersion() != 1
                    || !ticket.getId().equals(request.ticketId()) || !record.getTicket().getId().equals(ticket.getId())
                    || !ticket.getInquiry().getId().equals(request.inquiryId())
                    || !ticket.getInquiryProblem().getId().equals(request.problemId())
                    || (record.getLogType() != WorkLogType.RESOLVED
                        && record.getLogType() != WorkLogType.IMPLEMENTED)) unavailable();
            return new Stored(request, ticket, null);
        }
        var guide = messages.findResolution(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        var request = data.value(data.parse(guide.getPayload()).path("resolutionRequest"), ResolutionRequestResponse.class);
        var problem = problems.findById(request.problemId()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (guide.getInquiry().isDeleted() || problem.isDeleted()) notFound();
        if (!"GUIDE".equals(request.source()) || request.ticketId() != null || !request.sourceId().equals(guide.getId())
                || request.sourceVersion() != guide.getRevision() || guide.getSenderType() != SenderType.AI
                || !guide.getInquiry().getId().equals(request.inquiryId())
                || !problem.getInquiry().getId().equals(request.inquiryId())) unavailable();
        return new Stored(request, null, guide);
    }

    @Transactional(readOnly = true)
    public ResolutionRequestResponse get(Jwt jwt, UUID id) {
        var actor = auth.currentMember(jwt);
        var stored = stored(id);
        var inquiry = inquiries.findById(stored.request.inquiryId()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!inquiry.getRequester().getId().equals(actor.getId())) {
            if (stored.ticket == null) throw new BusinessException(ErrorCode.ACCESS_DENIED);
            authorization.requireCurrentPrimary(actor, stored.ticket);
        }
        return stored.request;
    }

    @Transactional(readOnly = true)
    public void checkResponse(Jwt jwt, UUID id) {
        var actor = auth.currentMember(jwt);
        var request = stored(id).request;
        var inquiry = inquiries.findById(request.inquiryId()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!inquiry.getRequester().getId().equals(actor.getId())) throw new BusinessException(ErrorCode.ACCESS_DENIED);
    }

    @Transactional
    public ResourceRef respond(Jwt jwt, UUID id, ResolutionResponseCommand command) {
        var initial = stored(id);
        var inquiry = inquiries.findForUpdate(initial.request.inquiryId()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (initial.ticket != null) entityManager.refresh(initial.ticket, LockModeType.PESSIMISTIC_WRITE);
        checkResponse(jwt, id);
        var stored = stored(id);
        var request = stored.request;
        if (!"PENDING".equals(request.state()) || command.expectedRequestRevision() != request.requestRevision()) {
            throw new BusinessException(ErrorCode.RESOLUTION_REVISION_CONFLICT);
        }
        boolean resolved = command.decision() == ResolutionResponseCommand.Decision.RESOLVED;
        Instant now = Instant.now();
        var response = new ResolutionRequestResponse(request.id(), request.inquiryId(), request.problemId(), request.ticketId(),
                request.source(), request.sourceId(), request.sourceVersion(), 2, command.decision().name(), request.resultSummary(),
                "HOPS_USER", request.createdAt(), now);
        if (stored.ticket != null) {
            var ticket = stored.ticket;
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
            var problem = problems.findById(request.problemId()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
            if (problem.getProblemStatus() != ProblemStatus.GUIDED) {
                throw new BusinessException(ErrorCode.RESOLUTION_REVISION_CONFLICT);
            }
            var payload = (ObjectNode) data.parse(stored.guide.getPayload());
            payload.set("resolutionRequest", mapper.valueToTree(response));
            stored.guide.recordResolution(mapper.writeValueAsString(payload));
            AgentRun run = null;
            if (!resolved) {
                run = runs.saveAndFlush(new AgentRun(inquiry, stored.guide, inquiry.getVersion(), "{}",
                        "pending", "pending", "hops-system-1"));
                run.prepareInput(inputs.guide(run, stored.guide, problem, response, auth.currentMember(jwt)));
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

    private static void unavailable() { throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE); }
    private static void notFound() { throw new BusinessException(ErrorCode.NOT_FOUND); }
}
