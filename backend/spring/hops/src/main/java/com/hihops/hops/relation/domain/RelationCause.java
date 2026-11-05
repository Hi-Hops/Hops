package com.hihops.hops.relation.domain;

import com.hihops.hops.itsystem.domain.ItSystem;
import com.hihops.hops.knowledge.domain.AccessLevel;
import com.hihops.hops.knowledge.domain.KnowledgeDocumentVersion;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** 가능 원인. 후보일 뿐 확정이 아니며 담당자 전용이다 (docs/ERD.md "원인 relation_cause"). */
@Entity
@Table(name = "relation_cause")
public class RelationCause extends RelationNode {

    protected RelationCause() {
    }

    public RelationCause(String catalogKey, String label, ItSystem itSystem, AccessLevel accessLevel,
            KnowledgeDocumentVersion sourceDocumentVersion) {
        super(catalogKey, label, itSystem, accessLevel, sourceDocumentVersion);
    }
}
