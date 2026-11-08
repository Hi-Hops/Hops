package com.hihops.hops.ticket.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.hihops.hops.ticket.domain.TicketAssignment;

/** ticket_assignment 테이블 저장·조회. 기본 save·findById는 JpaRepository가 제공한다. */
public interface TicketAssignmentRepository extends JpaRepository<TicketAssignment, UUID> {
    @EntityGraph(attributePaths = {"itSystem", "assignee.department", "assignedBy"})
    List<TicketAssignment> findByTicketIdAndDeletedAtIsNullOrderByAssignedAtAscIdAsc(UUID ticketId);

    /** 티켓의 저장된 책임 시스템·담당자와 같은 현재(종료되지 않은) 배정이 있는지. */
    boolean existsByTicketIdAndItSystemIdAndAssigneeIdAndEndedAtIsNullAndDeletedAtIsNull(
            UUID ticketId, UUID itSystemId, UUID assigneeId);
}
