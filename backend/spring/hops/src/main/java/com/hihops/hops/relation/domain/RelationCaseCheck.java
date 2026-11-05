package com.hihops.hops.relation.domain;

import com.hihops.hops.knowledge.domain.AccessLevel;
import com.hihops.hops.knowledge.domain.KnowledgeDocumentVersion;
import com.hihops.hops.knowledge.domain.ResolvedCase;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** 승인 사례 → 사례가 쓴 확인 항목 연결 (USES_CHECK). */
@Entity
@Table(name = "relation_case_check")
public class RelationCaseCheck extends RelationRecord {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "resolved_case_id", nullable = false)
    private ResolvedCase resolvedCase;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "check_item_id", nullable = false)
    private RelationCheckItem checkItem;

    protected RelationCaseCheck() {
    }

    public RelationCaseCheck(ResolvedCase resolvedCase, RelationCheckItem checkItem, AccessLevel accessLevel,
            KnowledgeDocumentVersion sourceDocumentVersion) {
        super(accessLevel, sourceDocumentVersion);
        this.resolvedCase = resolvedCase;
        this.checkItem = checkItem;
    }

    public ResolvedCase getResolvedCase() {
        return resolvedCase;
    }

    public RelationCheckItem getCheckItem() {
        return checkItem;
    }
}
