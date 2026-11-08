package com.hihops.hops.knowledge.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.hihops.hops.knowledge.domain.KnowledgeDocumentVersion;
import com.hihops.hops.knowledge.domain.ReadRule;

/** knowledge_document_version 테이블 저장·조회. 기본 save·findById는 JpaRepository가 제공한다. */
public interface KnowledgeDocumentVersionRepository extends JpaRepository<KnowledgeDocumentVersion, UUID> {
    Optional<KnowledgeDocumentVersion> findByKnowledgeDocumentIdAndVersionNo(UUID documentId, int versionNo);

    @Query(value = "SELECT v.* FROM knowledge_document_version v JOIN knowledge_document d ON d.id=v.knowledge_document_id "
            + "WHERE d.document_type='CASE' AND d.deleted_at IS NULL AND v.source_metadata ->> 'caseId' = :caseId "
            + "AND v.source_metadata ->> 'caseVersion' = CAST(:caseVersion AS text)", nativeQuery = true)
    Optional<KnowledgeDocumentVersion> findCaseVersion(String caseId, int caseVersion);

    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM knowledge_document_version v
                WHERE v.id = :versionId AND v.review_status = 'APPROVED'
                  AND v.version_no = (SELECT max(latest.version_no) FROM knowledge_document_version latest
                      WHERE latest.knowledge_document_id = v.knowledge_document_id AND latest.review_status = 'APPROVED'))
            """, nativeQuery = true)
    boolean isLatestApproved(UUID versionId);

    String CURRENT_VISIBILITY = """
              FROM knowledge_document_version v
              JOIN knowledge_document d ON d.id=v.knowledge_document_id
              LEFT JOIN it_system s ON s.id=d.it_system_id
              WHERE d.deleted_at IS NULL
              AND d.review_status='APPROVED' AND v.review_status='APPROVED' AND d.data_split='RAG'
              AND ((d.it_system_id IS NULL AND d.document_type='POLICY') OR (s.id IS NOT NULL AND s.deleted_at IS NULL))
              AND (d.access_level='EMPLOYEE' OR (d.access_level='STAFF' AND (
                :internalRead OR s.primary_owner_member_id=:memberId OR (d.it_system_id IS NULL AND EXISTS (
                  SELECT 1 FROM it_system owned WHERE owned.primary_owner_member_id=:memberId AND owned.deleted_at IS NULL)))))
              AND (d.document_type<>'CASE' OR EXISTS (
                SELECT 1 FROM resolved_case c JOIN ticket_work_log w ON w.id=c.source_work_log_id
                JOIN ticket t ON t.id=w.ticket_id
                WHERE CAST(c.id AS text)=v.source_metadata ->> 'caseId'
                AND CAST(c.version_no AS text)=v.source_metadata ->> 'caseVersion'
                AND c.deleted_at IS NULL AND c.case_status='APPROVED'
                AND c.it_system_id=d.it_system_id AND c.access_level=d.access_level
                AND c.approved_by_member_id IS NOT NULL AND c.approved_at IS NOT NULL
                AND (NOT :requireIndexed OR (c.vector_status='SUCCEEDED' AND c.graph_status='SUCCEEDED'))
                AND t.deleted_at IS NULL AND t.ticket_status='RESOLVED' AND t.resolved_at IS NOT NULL
                AND t.responsible_system_id=c.it_system_id
                AND w.log_type IN ('RESOLVED','IMPLEMENTED')))
            """;

    /**
     * 캐시된 엔티티 상태 대신 현재 DB에서 공개 가능 여부를 확인한다.
     *
     * @param requireIndexed CASE의 양쪽 색인 성공을 요구할지
     * @param internalRead 내부 판단에서 담당자 문서의 주담당자 조건을 생략할지
     */
    @Query(value = "SELECT EXISTS (SELECT 1 " + CURRENT_VISIBILITY + " AND v.id = :versionId)", nativeQuery = true)
    boolean matchesVisibility(UUID versionId, UUID memberId, boolean requireIndexed, boolean internalRead);

    /** 순위 조회와 반환 사이에 바뀐 승인 버전·범위·권한을 후보 전체에 대해 다시 확인한다. */
    @Query(value = "SELECT v.* " + CURRENT_VISIBILITY + """
            AND v.id IN (:versionIds) AND d.access_level IN (:levels) AND d.document_type IN (:types)
            AND (d.it_system_id IS NULL OR CAST(d.it_system_id AS text) IN (:systems))
            AND v.version_no = (SELECT max(latest.version_no) FROM knowledge_document_version latest
                  WHERE latest.knowledge_document_id = d.id AND latest.review_status = 'APPROVED')
            """, nativeQuery = true)
    List<KnowledgeDocumentVersion> findLatestVisibleVersions(Collection<UUID> versionIds, UUID memberId,
            boolean requireIndexed, boolean internalRead, Collection<String> levels, Collection<String> types,
            Collection<String> systems);

    /**
     * 현재 DB 조건으로 공개 가능 여부를 확인한다. 규칙은 {@link ReadRule}로 받는다.
     * 사용자에게 닿는 근거에는 {@code ReadRule.user(...)}만 쓴다.
     */
    default boolean isCurrentlyVisible(UUID versionId, UUID memberId, ReadRule rule) {
        return matchesVisibility(versionId, memberId, rule.requireIndexedCase(), rule.internalRead());
    }
}
