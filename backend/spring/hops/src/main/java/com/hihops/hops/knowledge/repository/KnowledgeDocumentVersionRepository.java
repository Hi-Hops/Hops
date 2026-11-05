package com.hihops.hops.knowledge.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.hihops.hops.knowledge.domain.KnowledgeDocumentVersion;

/** knowledge_document_version 테이블 저장·조회. 기본 save·findById는 JpaRepository가 제공한다. */
public interface KnowledgeDocumentVersionRepository extends JpaRepository<KnowledgeDocumentVersion, UUID> {
    Optional<KnowledgeDocumentVersion> findByKnowledgeDocumentIdAndVersionNo(UUID documentId, int versionNo);

    @Query(value = "SELECT v.* FROM knowledge_document_version v JOIN knowledge_document d ON d.id=v.knowledge_document_id "
            + "WHERE d.document_type='CASE' AND d.deleted_at IS NULL AND v.source_metadata ->> 'caseId' = :caseId "
            + "AND v.source_metadata ->> 'caseVersion' = CAST(:caseVersion AS text)", nativeQuery = true)
    Optional<KnowledgeDocumentVersion> findCaseVersion(String caseId, int caseVersion);

    /**
     * 캐시된 Entity의 과거 승인·ACL 대신 현재 DB 조건으로 공개 가능 여부를 확인한다.
     *
     * @param requireIndexed CASE에 Vector·Neo4j 반영 SUCCEEDED를 요구할지. 설정은 {@code KnowledgeVisibilityPolicy}가 정한다
     */
    @Query(value = """
            SELECT EXISTS (
              SELECT 1 FROM knowledge_document_version v
              JOIN knowledge_document d ON d.id=v.knowledge_document_id
              LEFT JOIN it_system s ON s.id=d.it_system_id
              WHERE v.id=:versionId AND d.deleted_at IS NULL
              AND d.review_status='APPROVED' AND v.review_status='APPROVED' AND d.data_split='RAG'
              AND ((d.it_system_id IS NULL AND d.document_type='POLICY') OR (s.id IS NOT NULL AND s.deleted_at IS NULL))
              AND (d.access_level='EMPLOYEE' OR (d.access_level='STAFF' AND (
                s.primary_owner_member_id=:memberId OR (d.it_system_id IS NULL AND EXISTS (
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
                AND w.log_type IN ('RESOLVED','IMPLEMENTED'))))
            """, nativeQuery = true)
    boolean isCurrentlyVisible(UUID versionId, UUID memberId, boolean requireIndexed);
}
