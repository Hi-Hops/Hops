package com.hihops.hops.inquiry.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.hihops.hops.inquiry.domain.StandardRequest;

/** standard_request 테이블 저장·조회. 기본 save·findById는 JpaRepository가 제공한다. */
public interface StandardRequestRepository extends JpaRepository<StandardRequest, UUID> {

    /**
     * 성공한 분석 또는 Ticket API journal로 업무 적용된 요청서. API 생성 뒤 AI가 실패해도 업무 기록은 보존한다.
     * 요청서는 버전마다 쌓이는 이력이고 현재 값은 최신 적용 실행의 행이다 (docs/ERD.md standard_request).
     */
    @Query(value = """
            SELECT s.* FROM standard_request s JOIN agent_run r ON r.id=s.agent_run_id
            WHERE s.inquiry_problem_id IN (:problemIds)
              AND (r.run_status='SUCCEEDED' OR EXISTS (
                  SELECT 1 FROM agent_ticket_request a
                  WHERE a.run_id=s.agent_run_id AND a.problem_id=s.inquiry_problem_id))
            ORDER BY s.inquiry_version DESC, s.created_at DESC, s.id DESC
            """, nativeQuery = true)
    List<StandardRequest> findAppliedNewestFirst(Collection<UUID> problemIds);
}
