package com.hihops.hops.knowledge.domain;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.hihops.hops.global.entity.BaseEntity;
import com.hihops.hops.itsystem.domain.ItSystem;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 지식 문서. 직원 가이드·정책·담당자 런북을 관리한다 (docs/ERD.md "지식 문서 knowledge_document").
 *
 * <p>검색에 쓰려면 세 조건을 모두 통과해야 한다: 검수 승인(APPROVED), 검색 자료(RAG), 보는 사람에게 허용된 공개 범위.
 * 본문은 이 테이블이 아니라 문서 버전(knowledge_document_version)에 버전별로 보관한다.
 * 해결 사례는 해결 사례(resolved_case) 테이블에 따로 둔다.
 */
@Entity
@Table(name = "knowledge_document")
public class KnowledgeDocument extends BaseEntity {

    @Column(nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 30)
    private DocumentType documentType;

    // 여러 시스템 공통 정책이면 null
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "it_system_id")
    private ItSystem itSystem;

    @Enumerated(EnumType.STRING)
    @Column(name = "access_level", nullable = false, length = 30)
    private AccessLevel accessLevel;

    // 출처 데이터셋·레코드 정보. 구조가 정해지면 전용 클래스로 바꾼다.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_data", columnDefinition = "jsonb")
    private String sourceData;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_split", nullable = false, length = 30)
    private DataSplit dataSplit;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 30)
    private ReviewStatus reviewStatus = ReviewStatus.PENDING;

    @Column(name = "is_synthetic", nullable = false)
    private boolean synthetic = true;

    protected KnowledgeDocument() {
    }

    public KnowledgeDocument(String title, DocumentType documentType, ItSystem itSystem, AccessLevel accessLevel,
            String sourceData, DataSplit dataSplit) {
        this.title = title;
        this.documentType = documentType;
        this.itSystem = itSystem;
        this.accessLevel = accessLevel;
        this.sourceData = sourceData;
        this.dataSplit = dataSplit;
    }

    public String getTitle() {
        return title;
    }

    public void approve() { reviewStatus = ReviewStatus.APPROVED; }

    public DocumentType getDocumentType() {
        return documentType;
    }

    public ItSystem getItSystem() {
        return itSystem;
    }

    public AccessLevel getAccessLevel() {
        return accessLevel;
    }

    public String getSourceData() {
        return sourceData;
    }

    public DataSplit getDataSplit() {
        return dataSplit;
    }

    public ReviewStatus getReviewStatus() {
        return reviewStatus;
    }

    public boolean isSynthetic() {
        return synthetic;
    }
}
