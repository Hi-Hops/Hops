package com.hihops.hops.relation.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hihops.hops.relation.domain.RelationCause;

/** relation_cause 조회. */
public interface RelationCauseRepository extends JpaRepository<RelationCause, UUID> {
    Optional<RelationCause> findByCatalogKeyAndDeletedAtIsNull(String catalogKey);
}
