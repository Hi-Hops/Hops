package com.hihops.hops.relation.domain;

import com.hihops.hops.knowledge.domain.AccessLevel;
import com.hihops.hops.knowledge.domain.KnowledgeDocumentVersion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 직원용 질문 문구. 확인 항목 하나에 여러 개를 둘 수 있고, 직원 상담은 승인되고 공개 표시가 있는 문구만 쓴다
 * (docs/ERD.md "질문 문구 relation_question_template").
 */
@Entity
@Table(name = "relation_question_template")
public class RelationQuestionTemplate extends RelationRecord {

    @Column(name = "catalog_key", nullable = false, length = 100)
    private String catalogKey;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "check_item_id", nullable = false)
    private RelationCheckItem checkItem;

    @Column(name = "question_text", nullable = false, length = 500)
    private String questionText;

    @Column(name = "employee_disclosable", nullable = false)
    private boolean employeeDisclosable;

    protected RelationQuestionTemplate() {
    }

    public RelationQuestionTemplate(String catalogKey, RelationCheckItem checkItem, String questionText,
            boolean employeeDisclosable, AccessLevel accessLevel, KnowledgeDocumentVersion sourceDocumentVersion) {
        super(accessLevel, sourceDocumentVersion);
        this.catalogKey = catalogKey;
        this.checkItem = checkItem;
        this.questionText = questionText;
        this.employeeDisclosable = employeeDisclosable;
    }

    public String getCatalogKey() {
        return catalogKey;
    }

    public RelationCheckItem getCheckItem() {
        return checkItem;
    }

    public String getQuestionText() {
        return questionText;
    }

    public boolean isEmployeeDisclosable() {
        return employeeDisclosable;
    }
}
