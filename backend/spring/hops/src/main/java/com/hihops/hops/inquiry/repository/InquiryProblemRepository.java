package com.hihops.hops.inquiry.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.hihops.hops.inquiry.domain.InquiryProblem;

/** inquiry_problem 테이블 저장·조회. 기본 save·findById는 JpaRepository가 제공한다. */
public interface InquiryProblemRepository extends JpaRepository<InquiryProblem, UUID> {
    @Query(value = """
            SELECT count(*) FROM inquiry_problem p LEFT JOIN ticket t ON t.inquiry_problem_id=p.id AND t.deleted_at IS NULL
            WHERE p.inquiry_id=:inquiryId AND p.deleted_at IS NULL
            AND p.problem_status NOT IN ('RESOLVED_WITHOUT_TICKET','OUT_OF_SCOPE')
            AND (t.id IS NULL OR t.ticket_status<>'RESOLVED')
            """, nativeQuery = true)
    long countUnresolved(UUID inquiryId);
}
