package com.hihops.hops.ticket.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Comparator;

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
import com.hihops.hops.ticket.dto.RecommendedCaseResponse;
import com.hihops.hops.ticket.dto.TicketAssistanceResponse;
import com.hihops.hops.ticket.dto.TicketAssistanceResponse.Material;
import com.hihops.hops.inquiry.repository.MessageRepository;
import com.hihops.hops.inquiry.domain.MessageStatus;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TicketCaseRecommendationService {
    private final TicketService tickets;
    private final AuthService auth;
    private final HybridKnowledgeSearch search;
    private final TicketStandardRequestReader standardRequests;
    private final CaseRecommendationExplanation explanation;
    private final TicketMaterialAssessment assessment;
    private final MessageRepository messages;
    private final RelatedMessageProjector projector;
    private final com.hihops.hops.itsystem.repository.SystemFeatureRepository features;

    @Transactional(readOnly = true)
    public TicketAssistanceResponse assistance(Jwt jwt, UUID ticketId) {
        var ticket = tickets.requireStaffTicket(jwt, ticketId);
        var member = auth.currentMember(jwt);
        var request = standardRequests.read(ticket.getStandardRequest(), member);
        var sourceIds = request.confirmedFacts().stream().flatMap(f -> f.sourceMessageIds().stream()).distinct().limit(8).toList();
        var sources = messages.findAllById(sourceIds).stream()
                .filter(m -> !m.isDeleted() && m.getMessageStatus() == MessageStatus.COMPLETED
                        && m.getInquiry().getId().equals(ticket.getInquiry().getId()) && m.getSenderType().name().equals("USER"))
                .flatMap(m -> projector.project(m, ticket.getInquiryProblem().getId(), List.of()).stream())
                .sorted(Comparator.comparing(s -> s.createdAt())).toList();
        String input = contextQuery(ticket.getTitle(), request, sources);
        var tokens = KnowledgeQueryTokens.of(input);
        var queries = List.of(DocumentType.CASE, DocumentType.EMPLOYEE_GUIDE, DocumentType.STAFF_RUNBOOK, DocumentType.POLICY)
                .stream().map(type -> new Query(tokens, Set.of(AccessLevel.EMPLOYEE, AccessLevel.STAFF), Set.of(type),
                        Set.of(ticket.getResponsibleSystem().getId()), member.getId(), type == DocumentType.CASE ? 20 : 6)).toList();
        try {
            var hits = search.searchIndexedGroups(input, queries);
            var recorded = ticket.getStandardRequest().getReportedFeature();
            boolean sameSystem = ticket.getStandardRequest().getReportedSystem().getId().equals(ticket.getResponsibleSystem().getId());
            String feature = recorded == null || !sameSystem ? null : featureLabel(recorded.getCode());
            if (feature == null && sameSystem) {
                var catalog = features.findActiveBySystemIds(Set.of(ticket.getResponsibleSystem().getId())).stream()
                        .map(com.hihops.hops.itsystem.dto.SystemFeatureResponse::from).toList();
                var sourceMap = sources.stream().collect(java.util.stream.Collectors.toMap(s -> s.id(), s -> List.of(s.content()),
                        (a,b) -> java.util.stream.Stream.concat(a.stream(), b.stream()).toList()));
                var explicit = com.hihops.hops.itsystem.service.ExplicitFeatureEvidence.resolve(catalog, request.confirmedFacts(), sourceMap);
                if (explicit != null) feature = explicit.name();
            }
            List<Material> materials = new ArrayList<>();
            for (var hit : hits) {
                var evidence = explanation.explain(input, ticket.getResponsibleSystem().getId(),
                        ticket.getResponsibleSystem().getName(), request, hit, CaseRecommendationExplanation.QueryScope.CONTEXT);
                var provenance = hit.assembled().caseProvenance();
                var sourceCase = provenance == null ? null : new TicketAssistanceResponse.CaseSource(
                        provenance.caseNumber(), provenance.ticketId(), provenance.ticketNumber(), provenance.approvedAt());
                materials.add(new Material(evidence, assessment.assess(evidence, request, sources, feature, hit.assembled().approvedParts()), sourceCase));
            }
            return new TicketAssistanceResponse(ticketId, ticket.getVersion() + 1, request.id(), input, sources,
                    TicketMaterialRanking.select(materials));
        } catch (IntegrityException | DataAccessException exception) {
            throw new BusinessException(ErrorCode.SEARCH_UNAVAILABLE);
        }
    }

    static String contextQuery(String title, com.hihops.hops.ticket.dto.StandardRequestResponse request) {
        return contextQuery(title, request, List.of());
    }

    static String contextQuery(String title, com.hihops.hops.ticket.dto.StandardRequestResponse request,
            List<com.hihops.hops.ticket.dto.RelatedMessageResponse> sources) {
        var lines = new java.util.LinkedHashSet<String>();
        lines.add(title);
        lines.add(request.summary());
        if (request.details() != null) {
            lines.add(request.details().taskOrScreen()); lines.add(request.details().actualBehavior());
        }
        request.confirmedFacts().stream().limit(12).forEach(f -> lines.add(f.text()));
        sources.stream().skip(Math.max(0, sources.size()-4)).forEach(s -> s.content().lines().forEach(lines::add));
        String input = String.join("\n", lines.stream().filter(s -> s != null && !s.isBlank()).toList());
        return input.length() > 2400 ? input.substring(0, input.offsetByCodePoints(0, Math.min(2000, input.codePointCount(0, input.length())))) : input;
    }

    private static String featureLabel(String code) {
        return switch(code) {
            case "DOCUMENT_VIEW" -> "문서 조회";
            case "DOCUMENT_CREATE" -> "문서 작성";
            case "APPROVAL_STATUS" -> "결재 상태";
            case "ATTACHMENT_VIEW" -> "첨부 열람";
            case "LOGIN_SSO" -> "공통 로그인";
            case "ACCOUNT_ACCESS" -> "계정 접근";
            default -> null;
        };
    }

    @Transactional(readOnly = true)
    public List<RecommendedCaseResponse> recommend(Jwt jwt, UUID ticketId) {
        var ticket = tickets.requireStaffTicket(jwt, ticketId);
        var member = auth.currentMember(jwt);
        String input = ticket.getTitle();
        var tokens = KnowledgeQueryTokens.of(input);
        if (tokens.isEmpty()) return List.of();
        var query = new Query(tokens, Set.of(AccessLevel.EMPLOYEE, AccessLevel.STAFF),
                Set.of(DocumentType.CASE), Set.of(ticket.getResponsibleSystem().getId()), member.getId(), 5);
        try {
            var hits = search.searchIndexedCases(input, query);
            if (hits.isEmpty()) return List.of();
            var request = standardRequests.read(ticket.getStandardRequest(), member);
            return hits.stream().map(hit -> explanation.explain(input, ticket.getResponsibleSystem().getId(),
                    ticket.getResponsibleSystem().getName(), request, hit)).toList();
        } catch (IntegrityException | DataAccessException exception) {
            throw new BusinessException(ErrorCode.SEARCH_UNAVAILABLE);
        }
    }
}
