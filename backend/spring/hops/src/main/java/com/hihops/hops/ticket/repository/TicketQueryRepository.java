package com.hihops.hops.ticket.repository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.hihops.hops.ticket.domain.Ticket;
import com.hihops.hops.ticket.domain.TicketStatus;

/** 티켓 화면 전용 조회. 티켓 저장·상태 전이 Repository와 분리한다. */
public interface TicketQueryRepository extends Repository<Ticket, UUID> {
    @Query(value = """
            SELECT t FROM Ticket t
            JOIN FETCH t.inquiry i JOIN FETCH t.inquiryProblem p
            JOIN FETCH t.standardRequest sr LEFT JOIN FETCH sr.reportedSystem
            JOIN FETCH t.responsibleSystem s JOIN FETCH t.assignee a JOIN FETCH a.department
            WHERE t.deletedAt IS NULL AND t.archivedAt IS NULL
            AND i.deletedAt IS NULL AND p.deletedAt IS NULL
            AND s.deletedAt IS NULL AND s.id IN :systemIds
            AND (:status IS NULL OR t.ticketStatus = :status)
            ORDER BY t.updatedAt DESC, t.id DESC
            """, countQuery = """
            SELECT count(t) FROM Ticket t
            WHERE t.deletedAt IS NULL AND t.archivedAt IS NULL
            AND t.inquiry.deletedAt IS NULL AND t.inquiryProblem.deletedAt IS NULL
            AND t.responsibleSystem.deletedAt IS NULL AND t.responsibleSystem.id IN :systemIds
            AND (:status IS NULL OR t.ticketStatus = :status)
            """)
    Page<Ticket> findVisible(Collection<UUID> systemIds, TicketStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"inquiry", "inquiryProblem", "standardRequest", "standardRequest.reportedSystem",
            "responsibleSystem", "assignee.department"})
    @Query("SELECT t FROM Ticket t WHERE t.id = :id AND t.deletedAt IS NULL "
            + "AND t.inquiry.deletedAt IS NULL AND t.inquiryProblem.deletedAt IS NULL AND t.responsibleSystem.deletedAt IS NULL")
    Optional<Ticket> findActiveDetail(UUID id);
}
