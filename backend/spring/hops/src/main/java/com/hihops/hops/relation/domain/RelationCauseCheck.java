package com.hihops.hops.relation.domain;

import com.hihops.hops.knowledge.domain.AccessLevel;
import com.hihops.hops.knowledge.domain.KnowledgeDocumentVersion;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** 원인 → 원인을 가르는 확인 항목 연결 (CHECKED_BY). */
@Entity
@Table(name = "relation_cause_check")
public class RelationCauseCheck extends RelationRecord {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cause_id", nullable = false)
    private RelationCause cause;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "check_item_id", nullable = false)
    private RelationCheckItem checkItem;

    protected RelationCauseCheck() {
    }

    public RelationCauseCheck(RelationCause cause, RelationCheckItem checkItem, AccessLevel accessLevel,
            KnowledgeDocumentVersion sourceDocumentVersion) {
        super(accessLevel, sourceDocumentVersion);
        this.cause = cause;
        this.checkItem = checkItem;
    }

    public RelationCause getCause() {
        return cause;
    }

    public RelationCheckItem getCheckItem() {
        return checkItem;
    }
}
