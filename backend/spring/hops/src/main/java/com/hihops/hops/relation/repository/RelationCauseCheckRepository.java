package com.hihops.hops.relation.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hihops.hops.relation.domain.RelationCauseCheck;

/** relation_cause_check 조회. 원인에서 확인 항목을 찾는다. */
public interface RelationCauseCheckRepository extends JpaRepository<RelationCauseCheck, UUID> {
    List<RelationCauseCheck> findByCauseIdAndDeletedAtIsNull(UUID causeId);
}
