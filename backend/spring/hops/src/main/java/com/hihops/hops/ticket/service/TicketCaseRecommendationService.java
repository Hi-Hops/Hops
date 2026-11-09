package com.hihops.hops.ticket.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hihops.hops.auth.service.AuthService;
import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.knowledge.domain.AccessLevel;
import com.hihops.hops.knowledge.domain.DocumentType;
import com.hihops.hops.knowledge.service.HybridKnowledgeSearch;
import com.hihops.hops.knowledge.service.KnowledgeEvidenceAssembler.IntegrityException;
import com.hihops.hops.knowledge.service.KnowledgeQueryTokens;
import com.hihops.hops.knowledge.service.KnowledgeSearchService.Query;
import com.hihops.hops.ticket.dto.StandardRequestResponse.KnowledgeEvidenceResponse;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TicketCaseRecommendationService {
    private final TicketService tickets;
    private final AuthService auth;
    private final HybridKnowledgeSearch search;

    @Transactional(readOnly = true)
    public List<KnowledgeEvidenceResponse> recommend(Jwt jwt, UUID ticketId) {
        var ticket = tickets.requireStaffTicket(jwt, ticketId);
        var member = auth.currentMember(jwt);
        String input = ticket.getTitle();
        var tokens = KnowledgeQueryTokens.of(input);
        if (tokens.isEmpty()) return List.of();
        var query = new Query(tokens, Set.of(AccessLevel.EMPLOYEE, AccessLevel.STAFF),
                Set.of(DocumentType.CASE), Set.of(ticket.getResponsibleSystem().getId()), member.getId(), 5);
        try {
            return search.searchIndexedCases(input, query).stream().map(hit -> hit.assembled().evidence()).toList();
        } catch (IntegrityException | DataAccessException exception) {
            throw new BusinessException(ErrorCode.SEARCH_UNAVAILABLE);
        }
    }
}
