package com.hihops.hops.ticket.dto;

import java.util.List;
import java.util.UUID;
import java.time.Instant;

/** 담당자의 현재 티켓 문맥과 현재 공개 가능한 근거. 원문 위치는 UTF-16, end exclusive다. */
public record TicketAssistanceResponse(UUID ticketId, int ticketRevision, UUID standardRequestId,
        String query, List<RelatedMessageResponse> sources, List<Material> materials) {
    public record CaseSource(String caseNumber, UUID ticketId, String ticketNumber, Instant approvedAt) {}
    public record GroupedCase(RecommendedCaseResponse evidence, Assessment assessment, CaseSource sourceCase) {}
    public record Material(RecommendedCaseResponse evidence, Assessment assessment, CaseSource sourceCase,
            List<GroupedCase> groupedCases) {
        public Material(RecommendedCaseResponse evidence, Assessment assessment) {
            this(evidence, assessment, null, List.of());
        }
        public Material(RecommendedCaseResponse evidence, Assessment assessment, CaseSource sourceCase) {
            this(evidence, assessment, sourceCase, List.of());
        }
    }
    public record Assessment(String relevance, String family, List<String> reasons,
            List<RecommendedCaseResponse.ConditionComparison> conditions,
            List<QuotePair> quotePairs, List<Section> sections, List<String> nextChecks,
            int matched, int unknown, int mismatched) {}
    public record QuotePair(String label, String status, UUID messageId, String currentQuote,
            int currentStart, int currentEnd, String sourceQuote, int sourceStart, int sourceEnd) {}
    public record Section(String label, String quote, int start, int end) {}
}
