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
     * 결과가 적용된(SUCCEEDED) 실행이 만든 요청서, 문제별 최신 것이 앞에 오도록 (문의 버전·생성 순 내림차순).
     * 요청서는 버전마다 쌓이는 이력이고 현재 값은 최신 적용 실행의 행이다 (docs/ERD.md standard_request).
     */
    @Query("""
            SELECT s FROM StandardRequest s
            WHERE s.inquiryProblem.id IN :problemIds
              AND s.agentRun.runStatus = com.hihops.hops.agent.domain.RunStatus.SUCCEEDED
            ORDER BY s.inquiryVersion DESC, s.createdAt DESC, s.id DESC
            """)
    List<StandardRequest> findAppliedNewestFirst(Collection<UUID> problemIds);
}
