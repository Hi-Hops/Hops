package com.hihops.hops.ticket.dto;

import java.util.List;
import java.util.UUID;

import com.hihops.hops.ticket.dto.StandardRequestResponse.KnowledgeEvidenceResponse;

/** 기존 근거 필드는 유지하고 담당자 추천에만 설명을 덧붙인다. */
public record RecommendedCaseResponse(UUID id, UUID documentId, int documentVersion, String kind, String title,
        String excerpt, UUID systemId, String appliesToSystemVersion, String applicability, String accessScope,
        String origin, Recommendation recommendation) {

    public static RecommendedCaseResponse of(KnowledgeEvidenceResponse evidence, Recommendation recommendation) {
        return new RecommendedCaseResponse(evidence.id(), evidence.documentId(), evidence.documentVersion(),
                evidence.kind(), evidence.title(), evidence.excerpt(), evidence.systemId(),
                evidence.appliesToSystemVersion(), evidence.applicability(), evidence.accessScope(), evidence.origin(),
                recommendation);
    }

    public record Recommendation(String query, double score, String scoreMethod, Integer keywordRank,
            Integer semanticRank, Double keywordScore, Double semanticSimilarity, List<String> matchedKeywords,
            List<String> reasons, List<ConditionComparison> comparisons) {
        public Recommendation {
            matchedKeywords = List.copyOf(matchedKeywords);
            reasons = List.copyOf(reasons);
            comparisons = List.copyOf(comparisons);
        }
    }

    public enum ComparisonStatus { MATCHED, MISMATCHED, UNKNOWN }

    /** currentEvidence는 권한을 통과한 확인 사실만 사용한다. 추정·다른 문제의 원문은 넣지 않는다. */
    public record ConditionComparison(String key, String label, ComparisonStatus status,
            List<String> currentEvidence, String caseEvidence) {
        public ConditionComparison {
            currentEvidence = List.copyOf(currentEvidence);
        }
    }
}
