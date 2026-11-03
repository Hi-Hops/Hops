package com.hihops.hops.knowledge.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hihops.hops.knowledge.domain.ResolvedCase;

/** resolved_case 테이블 저장·조회. 기본 save·findById는 JpaRepository가 제공한다. */
public interface ResolvedCaseRepository extends JpaRepository<ResolvedCase, UUID> {
    Optional<ResolvedCase> findBySourceWorkLogIdAndDeletedAtIsNull(UUID workLogId);
}
