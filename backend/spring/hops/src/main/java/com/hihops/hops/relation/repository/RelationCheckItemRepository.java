package com.hihops.hops.relation.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hihops.hops.relation.domain.RelationCheckItem;

/** relation_check_item 조회. */
public interface RelationCheckItemRepository extends JpaRepository<RelationCheckItem, UUID> {
    Optional<RelationCheckItem> findByCatalogKeyAndDeletedAtIsNull(String catalogKey);
}
