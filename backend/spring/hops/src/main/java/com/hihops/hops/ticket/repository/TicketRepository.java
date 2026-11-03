package com.hihops.hops.ticket.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hihops.hops.ticket.domain.Ticket;

/** ticket 테이블 저장·조회. 기본 save·findById는 JpaRepository가 제공한다. */
public interface TicketRepository extends JpaRepository<Ticket, UUID> {
}
