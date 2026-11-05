package com.hihops.hops.relation.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hihops.hops.relation.domain.RelationCaseCheck;

/** relation_case_check 조회. 사례가 쓴 확인 항목을 찾는다. */
public interface RelationCaseCheckRepository extends JpaRepository<RelationCaseCheck, UUID> {
    List<RelationCaseCheck> findByResolvedCaseIdAndDeletedAtIsNull(UUID resolvedCaseId);
}
