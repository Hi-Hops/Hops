package com.hihops.hops.relation.domain;

import com.hihops.hops.itsystem.domain.ItSystem;
import com.hihops.hops.knowledge.domain.AccessLevel;
import com.hihops.hops.knowledge.domain.KnowledgeDocumentVersion;

import jakarta.persistence.Column;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;

/** 증상·원인·확인 항목이 공통으로 가진 컬럼: 정본 카탈로그 ID, 이름, 시스템. */
@MappedSuperclass
public abstract class RelationNode extends RelationRecord {

    /** 정본 카탈로그 ID (예: sym-ea-doc-not-listed). 문서가 바뀌어도 같은 항목을 가리키려고 */
    @Column(name = "catalog_key", nullable = false, length = 100)
    private String catalogKey;

    @Column(nullable = false, length = 200)
    private String label;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "it_system_id", nullable = false)
    private ItSystem itSystem;

    protected RelationNode() {
    }

    protected RelationNode(String catalogKey, String label, ItSystem itSystem, AccessLevel accessLevel,
            KnowledgeDocumentVersion sourceDocumentVersion) {
        super(accessLevel, sourceDocumentVersion);
        this.catalogKey = catalogKey;
        this.label = label;
        this.itSystem = itSystem;
    }

    public String getCatalogKey() {
        return catalogKey;
    }

    public String getLabel() {
        return label;
    }

    public ItSystem getItSystem() {
        return itSystem;
    }
}
