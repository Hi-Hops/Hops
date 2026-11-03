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
import com.hihops.hops.realtime.dto.TicketChangedEvent;
import com.hihops.hops.ticket.domain.ActorType;
import com.hihops.hops.ticket.domain.TicketEvent;
import com.hihops.hops.ticket.domain.TicketEventType;
import com.hihops.hops.ticket.domain.TicketStatus;
import com.hihops.hops.ticket.domain.TicketWorkLog;
import com.hihops.hops.ticket.domain.TicketWorkType;
import com.hihops.hops.ticket.domain.WorkLogType;
import com.hihops.hops.ticket.dto.ResolutionRequestResponse;
import com.hihops.hops.ticket.dto.WorkRecordCommand;
import com.hihops.hops.ticket.repository.TicketEventRepository;
import com.hihops.hops.ticket.repository.TicketWorkLogRepository;

import lombok.RequiredArgsConstructor;

import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class WorkRecordService {
    private final TicketCommandAccess access;
    private final AuthService auth;
    private final TicketWorkLogRepository records;
    private final TicketEventRepository events;
    private final AgentRunRepository runs;
    private final ObjectMapper mapper;
    private final TicketAgentInput inputs;
    private final ApplicationEventPublisher publisher;

    @Transactional
    public ResourceRef save(Jwt jwt, UUID ticketId, WorkRecordCommand command) {
        var ticket = access.lock(jwt, ticketId, false);
        var actor = auth.currentMember(jwt);
        access.mutable(ticket, command.expectedTicketRevision());
        if (command.verified() && command.verification() == null) throw new BusinessException(ErrorCode.INVALID_INPUT);
        if (command.verified() && (ticket.getWorkType() == TicketWorkType.CHANGE_REQUEST
                ? command.kind() != WorkRecordCommand.Kind.IMPLEMENTED_VERIFIED
                : command.kind() != WorkRecordCommand.Kind.VERIFIED_RESOLUTION)) throw new BusinessException(ErrorCode.INVALID_INPUT);
        if (ticket.getTicketStatus() == TicketStatus.WAITING_CONFIRM) throw new BusinessException(ErrorCode.INVALID_STATE);
        WorkLogType kind = switch (command.kind()) {
            case PROGRESS -> WorkLogType.PROGRESS;
            case FAILED_ATTEMPT -> WorkLogType.FAILED_ATTEMPT;
            case VERIFIED_RESOLUTION -> WorkLogType.RESOLVED;
            case IMPLEMENTED_VERIFIED -> WorkLogType.IMPLEMENTED;
        };
        var before = ticket.getTicketStatus();
        var record = records.saveAndFlush(new TicketWorkLog(ticket, actor, kind, command.action(), command.confirmedCause(),
                command.verification(), command.appliesToSystemVersion()));
        Instant now = Instant.now();
        ticket.recordWork(command.verified(), now);
        var after = mapper.createObjectNode().put("state", ticket.getTicketStatus().name());
        if (command.verified()) {
            var request = new ResolutionRequestResponse(UUID.randomUUID(), ticket.getInquiry().getId(),
                    ticket.getInquiryProblem().getId(), ticketId, "WORK_RECORD", record.getId(), 1, 1, "PENDING",
                    command.action(), "HOPS_USER", now, null);
            after.set("resolutionRequest", mapper.valueToTree(request));
            // 업무·원 기록·실행 접수는 한 거래로 보존한다. 실제 AI 결과와 업무 저장 성공은 구분한다.
            var run = runs.saveAndFlush(new AgentRun(ticket.getInquiry(), ticket, record, ticket.getInquiry().getVersion(),
                    "{}", "pending", "pending", "hops-system-1"));
            run.prepareInput(inputs.curation(run, record, request, actor));
            publisher.publishEvent(new AgentRunQueued(run.getId()));
        }
        events.save(new TicketEvent(ticket, TicketEventType.STATUS_CHANGED, ActorType.MEMBER, actor, null,
                mapper.writeValueAsString(Map.of("state", before)), mapper.writeValueAsString(after), null));
        publisher.publishEvent(new TicketChangedEvent(ticketId, List.of(ticket.getResponsibleSystem().getId())));
        return new ResourceRef(ResourceType.TICKET, ticketId);
    }
}
