package com.hihops.hops.inquiry.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.hihops.hops.inquiry.domain.InquiryProblem;

/** inquiry_problem 테이블 저장·조회. 기본 save·findById는 JpaRepository가 제공한다. */
public interface InquiryProblemRepository extends JpaRepository<InquiryProblem, UUID> {
    @Query("SELECT COALESCE(MAX(p.problemNo), 0) FROM InquiryProblem p WHERE p.inquiry.id = :inquiryId")
    int findMaxProblemNo(UUID inquiryId);

    @Query(value = """
            SELECT count(*) FROM inquiry_problem p LEFT JOIN ticket t ON t.inquiry_problem_id=p.id AND t.deleted_at IS NULL
            WHERE p.inquiry_id=:inquiryId AND p.deleted_at IS NULL
            AND p.problem_status NOT IN ('RESOLVED_WITHOUT_TICKET','OUT_OF_SCOPE')
            AND (t.id IS NULL OR t.ticket_status<>'RESOLVED')
            """, nativeQuery = true)
    long countUnresolved(UUID inquiryId);

    /**
     * 문의별 문제 수·티켓 수·확인 대기 수 (내 문의 목록). 문제가 없는 문의는 결과에 없다.
     *
     * <p>확인 대기는 외부 상태 WAITING_CONFIRMATION으로 보이는 문제다 (docs/DATA.md 11.2):
     * 가이드 안내 뒤 확인을 기다리는 GUIDED, 또는 티켓이 확인 대기(WAITING_CONFIRM)인 TICKET_CREATED.
     * 티켓은 문제당 최대 1개라(uk_ticket_inquiry_problem) 문제에 붙여 세어도 중복되지 않는다.
     */
    @Query("""
            SELECT new com.hihops.hops.inquiry.repository.InquiryProblemCounts(
                p.inquiry.id,
                COUNT(p),
                COUNT(t),
                SUM(CASE WHEN p.problemStatus = com.hihops.hops.inquiry.domain.ProblemStatus.GUIDED
                           OR (p.problemStatus = com.hihops.hops.inquiry.domain.ProblemStatus.TICKET_CREATED
                               AND t.ticketStatus = com.hihops.hops.ticket.domain.TicketStatus.WAITING_CONFIRM)
                         THEN 1 ELSE 0 END))
            FROM InquiryProblem p
            LEFT JOIN Ticket t ON t.inquiryProblem = p AND t.deletedAt IS NULL
            WHERE p.inquiry.id IN :inquiryIds AND p.deletedAt IS NULL
            GROUP BY p.inquiry.id
            """)
    List<InquiryProblemCounts> countByInquiryIds(Collection<UUID> inquiryIds);

    boolean existsByInquiry_IdAndDeletedAtIsNull(UUID inquiryId);

    /** 문의의 삭제되지 않은 문제, 문제 번호 순. AI 입력의 problems를 만든다. */
    List<InquiryProblem> findByInquiry_IdAndDeletedAtIsNullOrderByProblemNo(UUID inquiryId);

    /** 삭제되지 않은 문제를 삭제되지 않은 본인 문의 조건과 함께 한 번에 찾는다 (작업 조회). */
    @Query("""
            SELECT p FROM InquiryProblem p JOIN FETCH p.inquiry i
            WHERE p.id = :problemId AND p.deletedAt IS NULL AND i.deletedAt IS NULL AND i.requester.id = :requesterId
            """)
    Optional<InquiryProblem> findMine(UUID problemId, UUID requesterId);
}
