package com.hihops.hops.knowledge.domain;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.hihops.hops.global.entity.CreatedOnlyEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 문서 버전. 지식 문서의 본문을 덮어쓰지 않고 버전별로 보관한다 (docs/ERD.md "문서 버전 knowledge_document_version").
 *
 * <p>내용을 고치면 기존 행을 바꾸지 않고 다음 버전 번호로 새 행을 추가한다. 그래서 예전 AI 답변이 어느 버전을
 * 참고했는지 나중에도 확인할 수 있다. 검색 조각(knowledge_chunk)은 이 버전에서 잘라낸다.
 *
 * <p>버전마다 원문 해시(contentDigest)·출처 메타데이터(sourceMetadata)·검수 상태(reviewStatus)를 고정한다 (docs/ERD.md 6.1절).
 * 검색 조각이 같은 원문에서 나왔는지 contentDigest로 대조하고, 승인된 버전만 검색에 쓴다.
 */
@Entity
@Table(name = "knowledge_document_version",
        uniqueConstraints = @UniqueConstraint(name = "uk_knowledge_document_version_document_version",
                columnNames = {"knowledge_document_id", "version_no"}))
public class KnowledgeDocumentVersion extends CreatedOnlyEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "knowledge_document_id", nullable = false)
    private KnowledgeDocument knowledgeDocument;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    // 승인 원문의 UTF-8 바이트 SHA-256 (16진수 64자)
    // 고정 길이 CHAR(64). VARCHAR로 기대하지 않게 JDBC 타입을 CHAR로 알려준다
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "content_digest", nullable = false, length = 64)
    private String contentDigest;

    // origin·출처·적용 조건. 사례 문서면 caseId·caseVersion·indexRequests도 둔다 (docs/DATA.md 11.3절)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_metadata", nullable = false, columnDefinition = "jsonb")
    private String sourceMetadata;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 30)
    private ReviewStatus reviewStatus = ReviewStatus.PENDING;

    protected KnowledgeDocumentVersion() {
    }

    public KnowledgeDocumentVersion(KnowledgeDocument knowledgeDocument, int versionNo, String content,
            String contentDigest, String sourceMetadata) {
        this.knowledgeDocument = knowledgeDocument;
        this.versionNo = versionNo;
        this.content = content;
        this.contentDigest = contentDigest;
        this.sourceMetadata = sourceMetadata;
    }

    public KnowledgeDocument getKnowledgeDocument() {
        return knowledgeDocument;
    }

    public int getVersionNo() {
        return versionNo;
    }

    public String getContent() {
        return content;
    }

    public String getContentDigest() {
        return contentDigest;
    }

    public String getSourceMetadata() {
        return sourceMetadata;
    }

    public ReviewStatus getReviewStatus() {
        return reviewStatus;
    }
}
