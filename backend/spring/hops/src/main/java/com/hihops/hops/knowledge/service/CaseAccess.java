package com.hihops.hops.knowledge.service;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.hihops.hops.auth.service.AuthService;
import com.hihops.hops.global.exception.*;
import com.hihops.hops.itsystem.repository.ItSystemQueryRepository;
import com.hihops.hops.knowledge.domain.ResolvedCase;
import com.hihops.hops.knowledge.repository.ResolvedCaseRepository;
import com.hihops.hops.ticket.service.TicketCommandAccess;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CaseAccess {
    private final AuthService auth;
    private final ItSystemQueryRepository systems;
    private final ResolvedCaseRepository cases;
    private final TicketCommandAccess tickets;
    private final EntityManager entities;

    @Transactional(readOnly = true)
    public ResolvedCase check(Jwt jwt, UUID id) {
        var actor = auth.currentMember(jwt);
        var value = cases.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        var ticket = value.getSourceWorkLog().getTicket();
        if (value.isDeleted() || value.getItSystem().isDeleted() || ticket.isDeleted()
                || ticket.getInquiry().isDeleted() || ticket.getInquiryProblem().isDeleted()) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (!systems.findActivePrimaryOwnerId(value.getItSystem().getId()).filter(actor.getId()::equals).isPresent()) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        return value;
    }

    /** 티켓 → 시스템 → 사례 순서로 잠가 재배정·해결 응답과 경합해도 같은 출처를 승인한다. */
    public ResolvedCase lock(Jwt jwt, UUID id) {
        var value = check(jwt, id);
        tickets.lock(jwt, value.getSourceWorkLog().getTicket().getId(), false);
        entities.refresh(value, LockModeType.PESSIMISTIC_WRITE);
        return check(jwt, id);
    }
}
