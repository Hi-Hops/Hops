package com.hihops.hops.agent.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.hihops.hops.agent.domain.AgentRun;

/** agent_run 테이블 저장·조회. 기본 save·findById는 JpaRepository가 제공한다. */
public interface AgentRunRepository extends JpaRepository<AgentRun, UUID> {
    @Query("SELECT r FROM AgentRun r JOIN FETCH r.triggerWorkLog w WHERE r.ticket.id = :ticketId "
            + "AND r.task = com.hihops.hops.agent.domain.AgentTask.CURATION ORDER BY w.createdAt, r.createdAt, r.id")
    List<AgentRun> findCurationsByTicketId(UUID ticketId);
}
