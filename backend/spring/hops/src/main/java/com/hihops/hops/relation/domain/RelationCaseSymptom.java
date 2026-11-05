package com.hihops.hops.relation.domain;

import com.hihops.hops.knowledge.domain.AccessLevel;
import com.hihops.hops.knowledge.domain.KnowledgeDocumentVersion;
import com.hihops.hops.knowledge.domain.ResolvedCase;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** 승인 사례 → 사례가 다룬 증상 연결 (ADDRESSES). 근거는 그 사례의 CASE 문서 버전이다. */
@Entity
@Table(name = "relation_case_symptom")
public class RelationCaseSymptom extends RelationRecord {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "resolved_case_id", nullable = false)
    private ResolvedCase resolvedCase;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "symptom_id", nullable = false)
    private RelationSymptom symptom;

    protected RelationCaseSymptom() {
    }

    public RelationCaseSymptom(ResolvedCase resolvedCase, RelationSymptom symptom, AccessLevel accessLevel,
            KnowledgeDocumentVersion sourceDocumentVersion) {
        super(accessLevel, sourceDocumentVersion);
        this.resolvedCase = resolvedCase;
        this.symptom = symptom;
    }

    public ResolvedCase getResolvedCase() {
        return resolvedCase;
    }

    public RelationSymptom getSymptom() {
        return symptom;
    }
}
