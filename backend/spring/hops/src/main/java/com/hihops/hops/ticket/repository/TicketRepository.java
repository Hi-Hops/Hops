package com.hihops.hops.ticket.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

import com.hihops.hops.ticket.domain.Ticket;

/** ticket 테이블 저장·조회. 기본 save·findById는 JpaRepository가 제공한다. */
public interface TicketRepository extends JpaRepository<Ticket, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Ticket t WHERE t.id = :id AND t.deletedAt IS NULL")
    Optional<Ticket> findForUpdate(UUID id);

    /** 문제의 티켓. 문제당 최대 1개다 (ticket.inquiry_problem_id 중복 불가). */
    Optional<Ticket> findByInquiryProblem_Id(UUID problemId);
}
