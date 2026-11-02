package com.hihops.hops.ticket.domain;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.hihops.hops.global.entity.BaseEntity;
import com.hihops.hops.itsystem.domain.ItSystem;
import com.hihops.hops.user.domain.Member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 담당 배정 이력. 티켓이 어느 시스템의 어느 담당자에게 언제 배정됐는지, 누가 왜 정정했는지 남긴다
 * (docs/ERD.md "담당 배정 이력 ticket_assignment").
 *
 * <p>endedAt이 null인 행이 현재 배정이며 티켓당 1건이다(DB 부분 유일 인덱스 uk_ticket_assignment_current).
 * 수동 정정은 현재 배정의 endedAt을 채우고 새 MANUAL_CORRECTION 배정을 추가한 뒤, 티켓의 책임 시스템·담당자를
 * 같은 트랜잭션으로 바꾼다. 자동 재이관은 없다.
 */
@Entity
@Table(name = "ticket_assignment")
public class TicketAssignment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false)
    private Ticket ticket;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "it_system_id", nullable = false)
    private ItSystem itSystem;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignee_member_id", nullable = false)
    private Member assignee;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AssignmentSource source;

    // 수동 정정한 사람. 자동 배정(AUTO)이면 null.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_by_member_id")
    private Member assignedBy;

    @Column(nullable = false, length = 1000)
    private String reason;

    // 배정 근거로 본 메시지와 메모 {"messageIds": [...], "note": ...} (docs/DATA.md 11.3절). 비어 있으면 빈 근거로 저장한다.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String evidence = "{\"messageIds\": [], \"note\": null}";

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    protected TicketAssignment() {
    }

    public TicketAssignment(Ticket ticket, ItSystem itSystem, Member assignee, AssignmentSource source,
            Member assignedBy, String reason, Instant assignedAt) {
        this.ticket = ticket;
        this.itSystem = itSystem;
        this.assignee = assignee;
        this.source = source;
        this.assignedBy = assignedBy;
        this.reason = reason;
        this.assignedAt = assignedAt;
    }

    public Ticket getTicket() {
        return ticket;
    }

    public ItSystem getItSystem() {
        return itSystem;
    }

    public Member getAssignee() {
        return assignee;
    }

    public AssignmentSource getSource() {
        return source;
    }

    public Member getAssignedBy() {
        return assignedBy;
    }

    public String getReason() {
        return reason;
    }

    public String getEvidence() {
        return evidence;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }
}
