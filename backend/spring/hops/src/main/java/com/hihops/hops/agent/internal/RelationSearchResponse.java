package com.hihops.hops.agent.internal;

import java.util.List;
import java.util.UUID;

import com.hihops.hops.relation.service.RelationSearchResult.Symptom;

/**
 * 관계 조회 응답 (contracts/agents/KNOWLEDGE_TOOLS.md "관계 조회"). outcome은 FOUND · NO_RESULT · DENIED이며
 * 검색 실패는 HTTP 503이다. 연결은 후보일 뿐이라 원인을 확정하는 필드는 없다.
 */
public record RelationSearchResponse(String contractVersion, UUID runId, String problemRef, String outcome,
        String deniedReason, Applied applied, List<Symptom> symptoms) {

    /** 서버가 실제로 적용한 범위. 요청이 아니라 실행 입력에서 정해진 값이다. */
    public record Applied(List<String> accessScopes, List<UUID> systemIds, int limit) {
    }
}
