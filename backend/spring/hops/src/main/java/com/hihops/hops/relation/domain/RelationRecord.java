package com.hihops.hops.relation.domain;

import com.hihops.hops.global.entity.BaseEntity;
import com.hihops.hops.knowledge.domain.AccessLevel;
import com.hihops.hops.knowledge.domain.KnowledgeDocumentVersion;
import com.hihops.hops.knowledge.domain.ReviewStatus;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;

/**
 * 관계 데이터 테이블이 모두 가진 공통 컬럼 (docs/ERD.md "관계 데이터").
 *
 * <p>모든 노드와 연결은 공개 범위(access_level), 승인 상태(review_status), 근거 문서 버전을 가진다.
 * 근거가 없는 연결은 만들지 않으므로 근거 문서 버전은 필수다. 승인되지 않은 항목은 상담에 쓰지 않는다.
 */
@MappedSuperclass
public abstract class RelationRecord extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "access_level", nullable = false, length = 30)
    private AccessLevel accessLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 30)
    private ReviewStatus reviewStatus = ReviewStatus.PENDING;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_document_version_id", nullable = false)
    private KnowledgeDocumentVersion sourceDocumentVersion;

    protected RelationRecord() {
    }

    protected RelationRecord(AccessLevel accessLevel, KnowledgeDocumentVersion sourceDocumentVersion) {
        this.accessLevel = accessLevel;
        this.sourceDocumentVersion = sourceDocumentVersion;
    }

    /** 검수를 마친 항목으로 표시한다. 승인된 항목만 상담에 쓴다. */
    public void approve() {
        this.reviewStatus = ReviewStatus.APPROVED;
    }

    public AccessLevel getAccessLevel() {
        return accessLevel;
    }

    public ReviewStatus getReviewStatus() {
        return reviewStatus;
    }

    public KnowledgeDocumentVersion getSourceDocumentVersion() {
        return sourceDocumentVersion;
    }
}
