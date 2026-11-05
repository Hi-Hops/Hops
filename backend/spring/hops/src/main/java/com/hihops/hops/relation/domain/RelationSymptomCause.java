package com.hihops.hops.relation.domain;

import com.hihops.hops.knowledge.domain.AccessLevel;
import com.hihops.hops.knowledge.domain.KnowledgeDocumentVersion;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** 증상 → 가능 원인 연결 (MAY_HAVE_CAUSE). 연결은 후보이며 원인을 확정하지 않는다. */
@Entity
@Table(name = "relation_symptom_cause")
public class RelationSymptomCause extends RelationRecord {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "symptom_id", nullable = false)
    private RelationSymptom symptom;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cause_id", nullable = false)
    private RelationCause cause;

    protected RelationSymptomCause() {
    }

    public RelationSymptomCause(RelationSymptom symptom, RelationCause cause, AccessLevel accessLevel,
            KnowledgeDocumentVersion sourceDocumentVersion) {
        super(accessLevel, sourceDocumentVersion);
        this.symptom = symptom;
        this.cause = cause;
    }

    public RelationSymptom getSymptom() {
        return symptom;
    }

    public RelationCause getCause() {
        return cause;
    }
}
