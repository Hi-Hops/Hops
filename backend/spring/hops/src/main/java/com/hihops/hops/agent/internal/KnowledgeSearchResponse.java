package com.hihops.hops.agent.internal;

import java.util.List;
import java.util.UUID;

import com.hihops.hops.knowledge.service.KnowledgeEvidenceAssembler.CaseIndexStatus;
import com.hihops.hops.ticket.dto.StandardRequestResponse.KnowledgeEvidenceResponse;

/**
 * 지식 검색 응답 (contracts/agents/KNOWLEDGE_TOOLS.md). outcome은 FOUND · NO_RESULT · DENIED이며,
 * 검색 실패는 이 본문이 아니라 HTTP 503이다. 오류를 빈 결과로 바꾸지 않는다.
 */
public record KnowledgeSearchResponse(String contractVersion, UUID runId, String problemRef, String outcome,
        String deniedReason, Applied applied, List<Result> results) {

    /** 서버가 실제로 적용한 범위. 요청이 아니라 실행 입력에서 정해진 값이다. */
    public record Applied(List<String> accessScopes, List<String> kinds, List<UUID> systemIds, int topK) {
    }

    /** caseIndexStatus는 CASE만 있으며 저장된 색인 상태를 그대로 둔다. */
    public record Result(KnowledgeEvidenceResponse evidence, String documentKey, String content, String contentDigest,
            String digestBasis, double score, CaseIndexStatus caseIndexStatus) {
    }
}
