package com.hihops.hops.ticket.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.hihops.hops.ticket.domain.TicketEvent;

/** ticket_event 테이블 저장·조회. 기본 save·findById는 JpaRepository가 제공한다. */
public interface TicketEventRepository extends JpaRepository<TicketEvent, UUID> {
    @Query(value = "SELECT * FROM ticket_event WHERE after_data->'resolutionRequest'->>'id'=CAST(:id AS text) ORDER BY created_at DESC,id DESC LIMIT 1", nativeQuery = true)
    Optional<TicketEvent> findResolution(UUID id);
    List<TicketEvent> findByTicketIdOrderByCreatedAtAscIdAsc(UUID ticketId);
}
