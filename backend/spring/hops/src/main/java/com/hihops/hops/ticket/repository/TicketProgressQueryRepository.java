package com.hihops.hops.ticket.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.knowledge.domain.ResolvedCase;

/** 티켓 화면에서 이미 저장된 사례 생성 상태만 읽는다. 실행 접수·결과 저장은 하지 않는다. */
public interface TicketProgressQueryRepository extends Repository<AgentRun, UUID> {
    @Query("SELECT r FROM AgentRun r WHERE r.id = :id")
    Optional<AgentRun> findById(UUID id);

    @Query("SELECT r FROM AgentRun r JOIN FETCH r.triggerWorkLog w WHERE r.ticket.id = :ticketId "
            + "AND r.task = com.hihops.hops.agent.domain.AgentTask.CURATION ORDER BY w.createdAt, r.createdAt, r.id")
    List<AgentRun> findCurationsByTicketId(UUID ticketId);

    @Query("SELECT c FROM ResolvedCase c WHERE c.sourceWorkLog.id = :workLogId AND c.deletedAt IS NULL")
    Optional<ResolvedCase> findCaseByWorkLogId(UUID workLogId);
}
