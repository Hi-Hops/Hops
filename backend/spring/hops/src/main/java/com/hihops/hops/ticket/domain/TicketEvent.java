package com.hihops.hops.ticket.domain;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.global.entity.CreatedOnlyEntity;
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
 * 티켓 변경 이력. 티켓의 생성·상태 변경·내용 정정·해결 확인 응답을 추가만 해서 남긴다
 * (docs/ERD.md "티켓 변경 이력 ticket_event").
 *
 * <p>before/after에 바뀌기 전후 값을 남겨서, 사람이 정정해도 AI가 처음 쓴 내용이 사라지지 않는다.
 * 담당 시스템·담당자 변경은 여기 넣지 않고 담당 배정 이력(ticket_assignment)에 남긴다.
 */
@Entity
@Table(name = "ticket_event")
public class TicketEvent extends CreatedOnlyEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false)
    private Ticket ticket;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private TicketEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 30)
    private ActorType actorType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_member_id")
    private Member actorMember;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_run_id")
    private AgentRun agentRun;

    // 구조는 이벤트 종류마다 다르고 아직 정해지지 않아 JSON 문자열로 둔다.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_data", columnDefinition = "jsonb")
    private String beforeData;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_data", columnDefinition = "jsonb")
    private String afterData;

    @Column(length = 1000)
    private String reason;

    protected TicketEvent() {
    }

    public TicketEvent(Ticket ticket, TicketEventType eventType, ActorType actorType, Member actorMember,
            AgentRun agentRun, String beforeData, String afterData, String reason) {
        this.ticket = ticket;
        this.eventType = eventType;
        this.actorType = actorType;
        this.actorMember = actorMember;
        this.agentRun = agentRun;
        this.beforeData = beforeData;
        this.afterData = afterData;
        this.reason = reason;
    }

    public Ticket getTicket() {
        return ticket;
    }

    public TicketEventType getEventType() {
        return eventType;
    }

    public ActorType getActorType() {
        return actorType;
    }

    public Member getActorMember() {
        return actorMember;
    }

    public AgentRun getAgentRun() {
        return agentRun;
    }

    public String getBeforeData() {
        return beforeData;
    }

    public String getAfterData() {
        return afterData;
    }

    public String getReason() {
        return reason;
    }
}
