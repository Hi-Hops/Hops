package com.hihops.hops.relation.domain;

import com.hihops.hops.itsystem.domain.ItSystem;
import com.hihops.hops.itsystem.domain.SystemFeature;
import com.hihops.hops.knowledge.domain.AccessLevel;
import com.hihops.hops.knowledge.domain.KnowledgeDocumentVersion;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** 증상. 직원이 말하는 현상이며 원인을 담지 않는다 (docs/ERD.md "증상 relation_symptom"). */
@Entity
@Table(name = "relation_symptom")
public class RelationSymptom extends RelationNode {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "system_feature_id", nullable = false)
    private SystemFeature systemFeature;

    protected RelationSymptom() {
    }

    public RelationSymptom(String catalogKey, String label, ItSystem itSystem, SystemFeature systemFeature,
            AccessLevel accessLevel, KnowledgeDocumentVersion sourceDocumentVersion) {
        super(catalogKey, label, itSystem, accessLevel, sourceDocumentVersion);
        this.systemFeature = systemFeature;
    }

    public SystemFeature getSystemFeature() {
        return systemFeature;
    }
}
