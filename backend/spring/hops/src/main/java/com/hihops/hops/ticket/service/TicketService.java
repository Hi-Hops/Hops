package com.hihops.hops.ticket.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hihops.hops.auth.service.AuthService;
import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.itsystem.domain.ItSystem;
import com.hihops.hops.itsystem.dto.SupportedSystemResponse;
import com.hihops.hops.itsystem.service.ItSystemService;
import com.hihops.hops.ticket.domain.Ticket;
import com.hihops.hops.ticket.domain.TicketAssignment;
import com.hihops.hops.ticket.dto.AssignmentHistoryResponse;
import com.hihops.hops.ticket.dto.CaseGenerationResponse;
import com.hihops.hops.ticket.dto.TicketDetailResponse;
import com.hihops.hops.ticket.dto.TicketListRequest;
import com.hihops.hops.ticket.dto.TicketManagementDetailResponse;
import com.hihops.hops.ticket.dto.TicketPageResponse;
import com.hihops.hops.ticket.dto.TicketScope;
import com.hihops.hops.ticket.dto.TicketState;
import com.hihops.hops.ticket.dto.TicketSummaryResponse;
import com.hihops.hops.ticket.dto.WorkRecordResponse;
import com.hihops.hops.ticket.repository.TicketAssignmentRepository;
import com.hihops.hops.ticket.repository.TicketRepository;
import com.hihops.hops.ticket.repository.TicketWorkLogRepository;
import com.hihops.hops.user.domain.Member;
import com.hihops.hops.user.domain.Role;
import com.hihops.hops.user.dto.UserSummaryResponse;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TicketService {
    private final AuthService authService;
    private final TicketAuthorization authorization;
    private final TicketRepository tickets;
    private final ItSystemService systems;
    private final TicketAssignmentRepository assignments;
    private final TicketWorkLogRepository workLogs;
    private final TicketStoredData data;
    private final TicketStandardRequestReader standardRequests;
    private final TicketProgressReader progress;

    @Transactional(readOnly = true)
    public CaseGenerationResponse caseGeneration(Jwt jwt, UUID generationId) {
        authService.currentMember(jwt);
        requireStaffTicket(jwt, progress.generationTicketId(generationId));
        return progress.generation(generationId);
    }

    @Transactional(readOnly = true)
    public void checkSystemSubscription(Jwt jwt, UUID systemId) {
        Member member = authService.currentMember(jwt);
        TicketScope scope = member.getContractRoles().contains(Role.BIZ_ADMIN) ? TicketScope.ADMIN : TicketScope.ASSIGNED_SYSTEMS;
        authorization.allowedSystemIds(member, scope, systemId);
    }

    @Transactional(readOnly = true)
    public Ticket requireStaffTicket(Jwt jwt, UUID ticketId) {
        Member member = authService.currentMember(jwt);
        Ticket ticket = findTicket(ticketId);
        authorization.requireCurrentPrimary(member, ticket);
        return ticket;
    }

    @Transactional(readOnly = true)
    public TicketDetailResponse detail(Jwt jwt, UUID ticketId) {
        Member member = authService.currentMember(jwt);
        Ticket ticket = findTicket(ticketId);
        authorization.requireCurrentPrimary(member, ticket);
        var records = workLogs.findByTicketIdOrderByCreatedAtAscIdAsc(ticketId);
        return TicketDetailResponse.of(summary(ticket), primary(ticket), assignmentHistory(ticket),
                standardRequests.read(ticket.getStandardRequest(), member),
                records.stream().map(WorkRecordResponse::from).toList(), progress.resolution(ticket, records),
                progress.generation(ticket));
    }

    @Transactional(readOnly = true)
    public TicketManagementDetailResponse managementDetail(Jwt jwt, UUID ticketId) {
        Member member = authService.currentMember(jwt);
        authorization.requireAdministrator(member);
        Ticket ticket = findTicket(ticketId);
        return TicketManagementDetailResponse.of(summary(ticket), primary(ticket), assignmentHistory(ticket));
    }

    private Ticket findTicket(UUID ticketId) {
        Ticket ticket = tickets.findActiveDetail(ticketId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        validateSources(ticket);
        return ticket;
    }

    private void validateSources(Ticket ticket) {
        if (!ticket.getInquiryProblem().getInquiry().getId().equals(ticket.getInquiry().getId())
                || !ticket.getStandardRequest().getInquiryProblem().getId().equals(ticket.getInquiryProblem().getId())
                || ticket.getStandardRequest().getReportedSystem() == null) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private UserSummaryResponse primary(Ticket ticket) {
        return UserSummaryResponse.from(authService.currentMember(authorization.currentPrimaryId(ticket)));
    }

    private List<AssignmentHistoryResponse> assignmentHistory(Ticket ticket) {
        var history = assignments.findByTicketIdAndDeletedAtIsNullOrderByAssignedAtAscIdAsc(ticket.getId());
        List<AssignmentHistoryResponse> result = new ArrayList<>();
        TicketAssignment previous = null;
        for (TicketAssignment value : history) {
            var evidence = data.parse(value.getEvidence());
            result.add(AssignmentHistoryResponse.of(value, previous, data.list(evidence.path("messageIds"), UUID.class),
                    evidence.path("note").asString(null)));
            previous = value;
        }
        return List.copyOf(result);
    }

    private TicketSummaryResponse summary(Ticket ticket) {
        if (ticket.getStandardRequest().getReportedSystem() == null) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        return summary(ticket, systems.responses(List.of(ticket.getStandardRequest().getReportedSystem(), ticket.getResponsibleSystem())));
    }

    @Transactional(readOnly = true)
    public TicketPageResponse list(Jwt jwt, TicketListRequest request) {
        Member member = authService.currentMember(jwt);
        var allowed = authorization.allowedSystemIds(member, request.resolvedScope(), request.resolvedSystemId());
        Page<Ticket> page = allowed.isEmpty() ? Page.empty(PageRequest.of(request.pageNumber(), request.pageSize()))
                : tickets.findVisible(allowed, request.state() == null ? null
                        : TicketState.valueOf(request.state()).databaseStatus(),
                        PageRequest.of(request.pageNumber(), request.pageSize()));
        var systemEntities = new ArrayList<ItSystem>();
        for (Ticket ticket : page) {
            validateSources(ticket);
            systemEntities.add(ticket.getStandardRequest().getReportedSystem());
            systemEntities.add(ticket.getResponsibleSystem());
        }
        Map<UUID, SupportedSystemResponse> catalog = systems.responses(systemEntities);
        return new TicketPageResponse(page.stream().map(ticket -> summary(ticket, catalog)).toList(),
                request.pageNumber(), request.pageSize(), page.getTotalElements());
    }

    private TicketSummaryResponse summary(Ticket ticket, Map<UUID, SupportedSystemResponse> catalog) {
        return TicketSummaryResponse.of(ticket, catalog.get(ticket.getStandardRequest().getReportedSystem().getId()),
                catalog.get(ticket.getResponsibleSystem().getId()));
    }
}
