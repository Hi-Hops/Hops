package com.hihops.hops.relation.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hihops.hops.relation.domain.RelationSymptom;

/** relation_symptom 조회. 정본 카탈로그 ID나 시스템으로 찾는다. */
public interface RelationSymptomRepository extends JpaRepository<RelationSymptom, UUID> {
    Optional<RelationSymptom> findByCatalogKeyAndDeletedAtIsNull(String catalogKey);

    List<RelationSymptom> findByItSystemIdAndDeletedAtIsNullOrderByCatalogKey(UUID itSystemId);
}
