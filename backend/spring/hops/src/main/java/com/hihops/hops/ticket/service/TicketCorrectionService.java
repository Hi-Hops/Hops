package com.hihops.hops.ticket.service;

import java.time.Instant;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import com.hihops.hops.auth.service.AuthService;
import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.global.idempotency.ResourceRef;
import com.hihops.hops.global.idempotency.ResourceType;
import com.hihops.hops.global.idempotency.CommandResult;
import com.hihops.hops.global.idempotency.IdempotencyService;
import com.hihops.hops.global.idempotency.IdempotentRequest;
import com.hihops.hops.itsystem.repository.ItSystemRepository;
import com.hihops.hops.itsystem.repository.ItSystemQueryRepository;
import com.hihops.hops.realtime.dto.TicketChangedEvent;
import com.hihops.hops.ticket.domain.ActorType;
import com.hihops.hops.ticket.domain.AssignmentSource;
import com.hihops.hops.ticket.domain.TicketAssignment;
import com.hihops.hops.ticket.domain.TicketEvent;
import com.hihops.hops.ticket.domain.TicketEventType;
import com.hihops.hops.ticket.domain.TicketWorkType;
import com.hihops.hops.ticket.dto.AssignmentCorrectionCommand;
import com.hihops.hops.ticket.dto.ProcessingTypeCorrectionCommand;
import com.hihops.hops.ticket.repository.TicketAssignmentRepository;
import com.hihops.hops.ticket.repository.TicketEventRepository;

import lombok.RequiredArgsConstructor;

import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
public class TicketCorrectionService {
    private final TicketCommandAccess access;
    private final AuthService auth;
    private final ItSystemRepository systems;
    private final TicketAssignmentRepository assignments;
    private final TicketEventRepository events;
    private final JsonMapper mapper;
    private final IdempotencyService idempotency;
    private final ItSystemQueryRepository systemQueries;
    private final Clock clock;
    private final ApplicationEventPublisher publisher;

    public CommandResult processingType(Jwt jwt, IdempotentRequest request, UUID id, ProcessingTypeCorrectionCommand command) {
        access.check(jwt, id, false);
        return idempotency.execute(request, operation -> correctType(jwt, id, command));
    }

    private ResourceRef correctType(Jwt jwt, UUID id, ProcessingTypeCorrectionCommand command) {
        var ticket = access.lock(jwt, id, false);
        var actor = auth.currentMember(jwt);
        access.mutable(ticket, command.expectedTicketRevision());
        access.evidence(ticket, actor, command.evidenceMessageIds(), command.evidenceNote());
        var before = ticket.getWorkType();
        var next = TicketWorkType.valueOf(command.processingType().name());
        if ((before != TicketWorkType.INVESTIGATION && before != TicketWorkType.INCIDENT) || before == next) {
            throw new BusinessException(ErrorCode.INVALID_STATE);
        }
        ticket.correctWorkType(next);
        var after = mapper.createObjectNode().put("processingType", next.name());
        after.set("evidenceMessageIds", mapper.valueToTree(command.evidenceMessageIds()));
        after.put("evidenceNote", command.evidenceNote());
        events.save(new TicketEvent(ticket, TicketEventType.WORK_TYPE_CHANGED, ActorType.MEMBER, actor, null,
                mapper.writeValueAsString(Map.of("processingType", before.name())), mapper.writeValueAsString(after), command.reason()));
        publisher.publishEvent(new TicketChangedEvent(id, List.of(ticket.getResponsibleSystem().getId())));
        return new ResourceRef(ResourceType.TICKET, id);
    }

    public CommandResult assignment(Jwt jwt, IdempotentRequest request, UUID id, AssignmentCorrectionCommand command) {
        access.check(jwt, id, true);
        return idempotency.execute(request, operation -> correctAssignment(jwt, id, command));
    }

    private ResourceRef correctAssignment(Jwt jwt, UUID id, AssignmentCorrectionCommand command) {
        var ticket = access.lock(jwt, id, true);
        var actor = auth.currentMember(jwt);
        access.mutable(ticket, command.expectedTicketRevision());
        access.evidence(ticket, actor, command.evidenceMessageIds(), command.evidenceNote());
        var next = systems.findForRead(command.responsibleSystemId()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        var owner = systemQueries.findActivePrimaryOwnerId(next.getId()).orElseThrow(() -> new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));
        var primary = auth.currentMember(owner);
        Instant now = clock.instant();
        var history = assignments.findByTicketIdAndDeletedAtIsNullOrderByAssignedAtAscIdAsc(id);
        var current = history.stream().filter(value -> value.getEndedAt() == null).toList();
        if (current.size() != 1) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        current.getFirst().end(now);
        assignments.flush(); // 현재 배정의 부분 유일 제약을 지키도록 이전 배정을 먼저 종료한다.
        var assignment = new TicketAssignment(ticket, next, primary, AssignmentSource.MANUAL_CORRECTION, actor, command.reason(), now);
        var evidence = mapper.createObjectNode();
        evidence.set("messageIds", mapper.valueToTree(command.evidenceMessageIds()));
        evidence.put("note", command.evidenceNote());
        assignment.recordEvidence(mapper.writeValueAsString(evidence));
        assignments.save(assignment);
        var previousSystem = ticket.getResponsibleSystem().getId();
        ticket.correctAssignment(next, primary);
        publisher.publishEvent(new TicketChangedEvent(id, List.of(previousSystem, next.getId())));
        return new ResourceRef(ResourceType.TICKET, id);
    }
}
