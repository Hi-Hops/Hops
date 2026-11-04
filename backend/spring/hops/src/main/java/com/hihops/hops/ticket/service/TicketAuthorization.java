package com.hihops.hops.ticket.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.itsystem.repository.ItSystemQueryRepository;
import com.hihops.hops.ticket.domain.Ticket;
import com.hihops.hops.ticket.dto.TicketScope;
import com.hihops.hops.user.domain.Member;
import com.hihops.hops.user.domain.Role;

import lombok.RequiredArgsConstructor;

/** 티켓의 업무 권한. HTTP·SSE 전송 계층과 독립적으로 현재 DB 관계를 확인한다. */
@Service
@RequiredArgsConstructor
public class TicketAuthorization {
    private final ItSystemQueryRepository systems;

    public List<UUID> allowedSystemIds(Member member, TicketScope scope, UUID requestedSystem) {
        List<UUID> allowed;
        if (scope == TicketScope.ADMIN) {
            requireAdministrator(member);
            allowed = systems.findActiveIds();
        } else {
            allowed = systems.findActiveIdsByPrimaryOwnerId(member.getId());
            if (allowed.isEmpty()) throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        if (requestedSystem != null) {
            if (!allowed.contains(requestedSystem)) throw new BusinessException(ErrorCode.ACCESS_DENIED);
            return List.of(requestedSystem);
        }
        return allowed;
    }

    public void requireAdministrator(Member member) {
        if (!member.getContractRoles().contains(Role.BIZ_ADMIN)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
    }

    public UUID currentPrimaryId(Ticket ticket) {
        return systems.findActivePrimaryOwnerId(ticket.getResponsibleSystem().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));
    }

    public void requireCurrentPrimary(Member member, Ticket ticket) {
        if (!currentPrimaryId(ticket).equals(member.getId())) throw new BusinessException(ErrorCode.ACCESS_DENIED);
    }

    public boolean isCurrentPrimary(Member member, Ticket ticket) {
        return systems.findActivePrimaryOwnerId(ticket.getResponsibleSystem().getId()).filter(member.getId()::equals).isPresent();
    }
}
