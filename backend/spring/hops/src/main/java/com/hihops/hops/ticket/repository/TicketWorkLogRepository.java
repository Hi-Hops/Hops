package com.hihops.hops.ticket.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.hihops.hops.ticket.domain.TicketWorkLog;

/** ticket_work_log 테이블 저장·조회. 기본 save·findById는 JpaRepository가 제공한다. */
public interface TicketWorkLogRepository extends JpaRepository<TicketWorkLog, UUID> {
    @EntityGraph(attributePaths = {"author.department"})
    List<TicketWorkLog> findByTicketIdOrderByCreatedAtAscIdAsc(UUID ticketId);
}
