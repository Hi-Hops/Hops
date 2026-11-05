package com.hihops.hops.relation.domain;

import com.hihops.hops.itsystem.domain.ItSystem;
import com.hihops.hops.knowledge.domain.AccessLevel;
import com.hihops.hops.knowledge.domain.KnowledgeDocumentVersion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** 확인 항목. 원인을 가르는 질문거리이며, 직원이 스스로 답할 수 있는 항목만 직원에게 물을 수 있다 (docs/ERD.md "확인 항목 relation_check_item"). */
@Entity
@Table(name = "relation_check_item")
public class RelationCheckItem extends RelationNode {

    @Column(name = "employee_disclosable", nullable = false)
    private boolean employeeDisclosable;

    protected RelationCheckItem() {
    }

    public RelationCheckItem(String catalogKey, String label, ItSystem itSystem, boolean employeeDisclosable,
            AccessLevel accessLevel, KnowledgeDocumentVersion sourceDocumentVersion) {
        super(catalogKey, label, itSystem, accessLevel, sourceDocumentVersion);
        this.employeeDisclosable = employeeDisclosable;
    }

    public boolean isEmployeeDisclosable() {
        return employeeDisclosable;
    }
}
