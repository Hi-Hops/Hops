package com.hihops.hops.ticket.dto;

import java.util.List;
import java.util.UUID;

/** DATA_TYPES 신규 StandardRequest. 저장 JSONB와 외부 응답을 분리한다. */
public record StandardRequestResponse(UUID id, UUID problemId, int inquiryVersion, String summary,
        List<EvidenceStatementResponse> confirmedFacts, List<EvidenceStatementResponse> assumptions, List<String> unknowns,
        UUID responsibleSystemCandidateId, List<UUID> responsibilityEvidenceIds, RequestDetailsResponse details,
        List<KnowledgeEvidenceResponse> knowledge) {
    public record EvidenceStatementResponse(String text, List<UUID> sourceMessageIds, List<UUID> knowledgeEvidenceIds) {
        public EvidenceStatementResponse {
            if (text == null || text.isBlank()) throw new IllegalArgumentException("사실 본문 누락");
            sourceMessageIds = List.copyOf(sourceMessageIds);
            knowledgeEvidenceIds = List.copyOf(knowledgeEvidenceIds);
        }
    }

    public record RequestDetailsResponse(String taskOrScreen, String requestedAccess, String businessPurpose,
            String expectedBehavior, String actualBehavior, String occurredAt, String currentWorkflow,
            String desiredChange, List<AcceptanceCriterionResponse> acceptanceCriteria) {
        public RequestDetailsResponse {
            if (java.util.stream.Stream.of(taskOrScreen, requestedAccess, businessPurpose, expectedBehavior,
                    actualBehavior, occurredAt, currentWorkflow, desiredChange)
                    .anyMatch(value -> value != null && value.isBlank())) {
                throw new IllegalArgumentException("미확인 상세 값은 빈 문자열 대신 null이어야 한다");
            }
            acceptanceCriteria = acceptanceCriteria == null ? List.of() : List.copyOf(acceptanceCriteria);
        }
    }

    public record AcceptanceCriterionResponse(String text, String confirmation, UUID confirmedBy) {
        public AcceptanceCriterionResponse {
            if (text == null || text.isBlank()
                    || !List.of("PROPOSED", "CONFIRMED").contains(confirmation)
                    || ("CONFIRMED".equals(confirmation) != (confirmedBy != null))) {
                throw new IllegalArgumentException("인수 조건 확인 메타데이터 오류");
            }
        }
    }

    public record KnowledgeEvidenceResponse(UUID id, UUID documentId, int documentVersion, String kind, String title,
            String excerpt, UUID systemId, String appliesToSystemVersion, String applicability, String accessScope,
            String origin) {
        public KnowledgeEvidenceResponse {
            if (id == null || documentId == null || documentVersion < 1 || title == null || title.isBlank()
                    || excerpt == null || excerpt.isBlank() || applicability == null || applicability.isBlank()
                    || !List.of("GUIDE", "POLICY", "RUNBOOK", "CASE").contains(kind)
                    || !List.of("EMPLOYEE", "SYSTEM_STAFF").contains(accessScope)
                    || !List.of("SYNTHETIC", "DEMO_VERIFIED", "OPERATIONAL").contains(origin)
                    || (systemId == null && !"POLICY".equals(kind))) {
                throw new IllegalArgumentException("근거 메타데이터 오류");
            }
        }
    }
}
