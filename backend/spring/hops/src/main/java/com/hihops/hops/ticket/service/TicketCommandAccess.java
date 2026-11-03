package com.hihops.hops.ticket.service;

import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hihops.hops.auth.service.AuthService;
import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.inquiry.repository.InquiryRepository;
import com.hihops.hops.itsystem.repository.ItSystemRepository;
import com.hihops.hops.ticket.domain.Ticket;
import com.hihops.hops.ticket.domain.TicketStatus;
import com.hihops.hops.ticket.repository.TicketRepository;
import com.hihops.hops.user.domain.Member;
import com.hihops.hops.user.domain.Role;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import lombok.RequiredArgsConstructor;

/** 재확인에도 현재 권한을 검사하고, 최초 변경은 집계 잠금 안에서 다시 검증한다. */
@Service
@RequiredArgsConstructor
public class TicketCommandAccess {
    private final AuthService auth;
    private final TicketService queries;
    private final TicketRepository tickets;
    private final ItSystemRepository systems;
    private final TicketAuthorization authorization;
    private final TicketEvidence evidence;
    private final InquiryRepository inquiries;
    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public void check(Jwt jwt, UUID id, boolean adminAllowed) {
        if (adminAllowed && auth.currentMember(jwt).getContractRoles().contains(Role.BIZ_ADMIN)) {
            queries.managementDetail(jwt, id);
        } else {
            queries.requireStaffTicket(jwt, id);
        }
    }

    public Ticket lock(Jwt jwt, UUID id, boolean adminAllowed) {
        var target = tickets.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        inquiries.findForUpdate(target.getInquiry().getId()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Ticket ticket = tickets.findForUpdate(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        entityManager.refresh(ticket, LockModeType.PESSIMISTIC_WRITE);
        systems.findForRead(ticket.getResponsibleSystem().getId()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Member actor = auth.currentMember(jwt);
        if (!adminAllowed || !actor.getContractRoles().contains(Role.BIZ_ADMIN)) authorization.requireCurrentPrimary(actor, ticket);
        if (ticket.getInquiry().isDeleted() || ticket.getInquiryProblem().isDeleted()) throw new BusinessException(ErrorCode.NOT_FOUND);
        return ticket;
    }

    public void mutable(Ticket ticket, int expected) {
        if (ticket.getVersion() + 1 != expected) throw new BusinessException(ErrorCode.TICKET_REVISION_CONFLICT);
        if (ticket.getTicketStatus() == TicketStatus.RESOLVED) throw new BusinessException(ErrorCode.INVALID_STATE);
    }

    public void evidence(Ticket ticket, Member actor, List<UUID> ids, String note) {
        if (ids.isEmpty() && note == null) throw new BusinessException(ErrorCode.INVALID_INPUT);
        if (!evidence.visible(ticket, actor, ids).containsAll(ids)) throw new BusinessException(ErrorCode.ACCESS_DENIED);
    }
}
