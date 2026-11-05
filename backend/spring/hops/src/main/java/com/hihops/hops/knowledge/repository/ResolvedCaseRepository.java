package com.hihops.hops.knowledge.repository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.hihops.hops.knowledge.domain.CaseStatus;
import com.hihops.hops.knowledge.domain.ResolvedCase;

/** resolved_case 테이블 저장·조회. 기본 save·findById는 JpaRepository가 제공한다. */
public interface ResolvedCaseRepository extends JpaRepository<ResolvedCase, UUID> {

    /** 사례 검토 목록. 계약 정렬(updatedAt DESC, id DESC)을 따르고 삭제된 사례·시스템은 뺀다. */
    @Query(value = """
            SELECT c FROM ResolvedCase c
            WHERE c.itSystem.id IN :systemIds AND c.deletedAt IS NULL AND c.itSystem.deletedAt IS NULL
            AND (:status IS NULL OR c.caseStatus = :status)
            ORDER BY c.updatedAt DESC, c.id DESC
            """, countQuery = """
            SELECT count(c) FROM ResolvedCase c
            WHERE c.itSystem.id IN :systemIds AND c.deletedAt IS NULL AND c.itSystem.deletedAt IS NULL
            AND (:status IS NULL OR c.caseStatus = :status)
            """)
    Page<ResolvedCase> findVisible(Collection<UUID> systemIds, CaseStatus status, Pageable pageable);

    /** 사례 상세. 사례·시스템·원 티켓·문의·문제 중 하나라도 삭제됐으면 찾지 않는다. */
    @EntityGraph(attributePaths = {"itSystem", "sourceWorkLog", "sourceWorkLog.ticket", "sourceWorkLog.ticket.inquiry",
            "sourceWorkLog.ticket.inquiryProblem", "approvedBy", "approvedBy.department"})
    @Query("""
            SELECT c FROM ResolvedCase c
            WHERE c.id = :id AND c.deletedAt IS NULL AND c.itSystem.deletedAt IS NULL
            AND c.sourceWorkLog.ticket.deletedAt IS NULL AND c.sourceWorkLog.ticket.inquiry.deletedAt IS NULL
            AND c.sourceWorkLog.ticket.inquiryProblem.deletedAt IS NULL
            """)
    Optional<ResolvedCase> findActiveDetail(UUID id);
}
