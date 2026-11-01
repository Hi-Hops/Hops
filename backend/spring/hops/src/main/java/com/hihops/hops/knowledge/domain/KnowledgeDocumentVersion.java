package com.hihops.hops.knowledge.domain;

import com.hihops.hops.global.entity.CreatedOnlyEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

    protected KnowledgeDocumentVersion() {
    }

    public KnowledgeDocumentVersion(KnowledgeDocument knowledgeDocument, int versionNo, String content) {
        this.knowledgeDocument = knowledgeDocument;
        this.versionNo = versionNo;
        this.content = content;
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
}
