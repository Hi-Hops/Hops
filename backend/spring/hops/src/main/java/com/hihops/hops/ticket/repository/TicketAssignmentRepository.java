package com.hihops.hops.ticket.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hihops.hops.ticket.domain.TicketAssignment;

/** ticket_assignment 테이블 저장·조회. 기본 save·findById는 JpaRepository가 제공한다. */
public interface TicketAssignmentRepository extends JpaRepository<TicketAssignment, UUID> {
}
