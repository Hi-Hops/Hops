package com.hihops.hops.knowledge.service;

import java.util.Arrays;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.knowledge.domain.CaseStatus;
import com.hihops.hops.knowledge.domain.IndexStore;
import com.hihops.hops.knowledge.domain.ResolvedCase;
import com.hihops.hops.knowledge.repository.KnowledgeDocumentVersionRepository;
import com.hihops.hops.knowledge.repository.ResolvedCaseRepository;

import lombok.RequiredArgsConstructor;

/**
 * AI 색인 작업이 읽는 승인 사례의 색인 원본 (contracts/agents I1). AI 계정은 업무 테이블을 읽지 않으므로
 * 승인 거래가 만든 검색 문서 버전의 ID·본문·digest를 이 조회로만 받는다.
 *
 * <p>승인된 현재 버전이고 아직 반영이 끝나지 않은 저장소가 있을 때만 돌려준다. 그 밖에는 존재 여부를 드러내지 않도록 404다.
 * AI는 받은 contentDigest가 요청의 승인 본문에서 계산한 사례 digest와 같은지 확인한 뒤에만 반영한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CaseIndexSourceService {
    private final ResolvedCaseRepository cases;
    private final KnowledgeDocumentVersionRepository versions;

    public CaseIndexSource find(UUID caseId, int caseVersion) {
        ResolvedCase resolved = cases.findById(caseId)
                .filter(c -> c.getCaseStatus() == CaseStatus.APPROVED && c.getDeletedAt() == null
                        && c.getVersionNo() == caseVersion)
                .filter(c -> Arrays.stream(IndexStore.values()).anyMatch(store -> c.indexStatus(store).isOpen()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        var version = versions.findCaseVersion(caseId.toString(), caseVersion)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return new CaseIndexSource(CaseApprovalWriter.CONTRACT_VERSION, caseId.toString(), caseVersion,
                resolved.getCaseNumber(), version.getId().toString(), resolved.getTitle(), resolved.getApplicability(),
                version.getContent(), version.getContentDigest());
    }

    /**
     * 색인 원본. contentDigest는 승인 때 저장한 사례 스냅샷 digest({approvedCase, caseId, caseVersion})다.
     *
     * @param caseNumber        그래프 사례 노드의 정렬 키(Neo4jRelationReader가 case_number로 정렬한다)
     * @param documentVersionId 검색 조각이 FK로 가리킬 knowledge_document_version.id
     */
    public record CaseIndexSource(String contractVersion, String caseId, int caseVersion, String caseNumber,
            String documentVersionId, String title, String applicability, String content, String contentDigest) {}
}
