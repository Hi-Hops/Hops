package com.hihops.hops.relation.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hihops.hops.relation.domain.RelationSymptomCause;

/** relation_symptom_cause 조회. 증상에서 가능 원인 후보를 찾는다. */
public interface RelationSymptomCauseRepository extends JpaRepository<RelationSymptomCause, UUID> {
    List<RelationSymptomCause> findBySymptomIdAndDeletedAtIsNull(UUID symptomId);
}
