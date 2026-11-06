package com.hihops.hops.relation.service;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.hihops.hops.knowledge.service.KnowledgeEvidenceAssembler.CaseIndexStatus;
import com.hihops.hops.ticket.dto.StandardRequestResponse.KnowledgeEvidenceResponse;

/**
 * 관계 조회 결과 (contracts/agents/KNOWLEDGE_TOOLS.md "관계 조회"). 연결은 후보일 뿐이라 원인을 확정하는 값은 없다.
 * 직원 전용 조회에서는 causes가 null이고 확인 항목의 label이 null이다.
 */
public final class RelationSearchResult {
    private RelationSearchResult() {
    }

    /** 항목을 선언한 근거 문서 버전. 이 실행에 공개할 수 없는 문서면 visible=false이고 나머지는 null이다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Basis(boolean visible, UUID documentId, Integer documentVersion, String documentKey) {
        static Basis hidden() {
            return new Basis(false, null, null, null);
        }
    }

    public record Question(UUID id, String catalogKey, String text, Basis basis) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CheckItem(UUID id, String catalogKey, String label, boolean employeeDisclosable, String accessScope, Basis basis,
            List<Question> questions) {
    }

    public record Cause(UUID id, String catalogKey, String label, String accessScope, Basis basis, List<String> checkItemKeys) {
    }

    public record CaseNeighbor(String documentKey, KnowledgeEvidenceResponse evidence, CaseIndexStatus caseIndexStatus) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Symptom(UUID id, String catalogKey, String label, UUID systemId, UUID featureId, String featureCode,
            String accessScope, Basis basis, double score, List<CheckItem> checkItems, List<Cause> causes, List<CaseNeighbor> cases) {
    }
}
