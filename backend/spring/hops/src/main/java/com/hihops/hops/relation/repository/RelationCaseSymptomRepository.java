package com.hihops.hops.relation.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hihops.hops.relation.domain.RelationCaseSymptom;

/** relation_case_symptom 조회. 사례가 다룬 증상, 증상을 다룬 사례를 찾는다. */
public interface RelationCaseSymptomRepository extends JpaRepository<RelationCaseSymptom, UUID> {
    List<RelationCaseSymptom> findByResolvedCaseIdAndDeletedAtIsNull(UUID resolvedCaseId);

    List<RelationCaseSymptom> findBySymptomIdAndDeletedAtIsNull(UUID symptomId);
}
