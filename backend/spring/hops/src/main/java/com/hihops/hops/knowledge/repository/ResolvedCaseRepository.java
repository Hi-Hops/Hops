package com.hihops.hops.knowledge.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hihops.hops.knowledge.domain.ResolvedCase;

/** resolved_case 테이블 저장·조회. 기본 save·findById는 JpaRepository가 제공한다. */
public interface ResolvedCaseRepository extends JpaRepository<ResolvedCase, UUID> {
    @org.springframework.data.jpa.repository.Query("SELECT c FROM ResolvedCase c WHERE c.deletedAt IS NULL "
            + "AND c.itSystem.id IN :systems AND c.sourceWorkLog.ticket.deletedAt IS NULL "
            + "AND c.sourceWorkLog.ticket.inquiry.deletedAt IS NULL "
            + "AND c.sourceWorkLog.ticket.inquiryProblem.deletedAt IS NULL "
            + "AND (:state IS NULL OR c.caseStatus = :state) ORDER BY c.updatedAt DESC, c.id DESC")
    org.springframework.data.domain.Page<ResolvedCase> findVisible(java.util.List<UUID> systems,
            com.hihops.hops.knowledge.domain.CaseStatus state, org.springframework.data.domain.Pageable page);
}
