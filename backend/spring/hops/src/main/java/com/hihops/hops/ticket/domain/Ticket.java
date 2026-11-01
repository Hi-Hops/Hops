package com.hihops.hops.ticket.domain;

import java.time.Instant;

import com.hihops.hops.global.entity.BaseEntity;
import com.hihops.hops.inquiry.domain.Inquiry;
import com.hihops.hops.inquiry.domain.InquiryProblem;
import com.hihops.hops.inquiry.domain.StandardRequest;
import com.hihops.hops.itsystem.domain.ItSystem;
import com.hihops.hops.user.domain.Member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 티켓. 책임 시스템의 주담당자가 처리할 일 1건이다 (docs/ERD.md "티켓 ticket").
 *
 * <p>문제 하나에서 티켓은 최대 1개라 문의 문제와 1:1이다(inquiry_problem_id 중복 불가). 같은 문제의 티켓을 다시 만들려 하면
 * DB가 막으므로, 생성 응답이 유실돼 재시도해도 티켓이 두 개 생기지 않는다.
 *
 * <p>assignee는 배정 시점의 책임 시스템 주담당자다. 나중에 시스템 주담당자가 바뀌어도 이미 배정된 티켓의 담당은 그대로 남는다.
 * 배정·수동 정정 내역은 담당 배정 이력(ticket_assignment)에 따로 남긴다.
 *
 * <p>version은 JPA {@code @Version}(낙관적 잠금)이다. 두 사람이 같은 티켓을 동시에 고치면 늦게 저장한 쪽이 실패한다.
 * 문의 버전(Inquiry.version)과는 다른 값이다.
 */
@Entity
@Table(name = "ticket")
public class Ticket extends BaseEntity {

    @Column(name = "ticket_number", nullable = false, unique = true, length = 20)
    private String ticketNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inquiry_id", nullable = false)
    private Inquiry inquiry;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inquiry_problem_id", nullable = false, unique = true)
    private InquiryProblem inquiryProblem;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "standard_request_id", nullable = false)
    private StandardRequest standardRequest;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(name = "work_type", nullable = false, length = 30)
    private TicketWorkType workType;

    @Enumerated(EnumType.STRING)
    @Column(name = "ticket_status", nullable = false, length = 30)
    private TicketStatus ticketStatus = TicketStatus.OPEN;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "responsible_system_id", nullable = false)
    private ItSystem responsibleSystem;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignee_member_id", nullable = false)
    private Member assignee;

    @Column(name = "confirm_requested_at")
    private Instant confirmRequestedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Version
    @Column(nullable = false)
    private int version;

    protected Ticket() {
    }

    public Ticket(String ticketNumber, Inquiry inquiry, InquiryProblem inquiryProblem, StandardRequest standardRequest,
            String title, String detail, TicketWorkType workType, ItSystem responsibleSystem, Member assignee) {
        this.ticketNumber = ticketNumber;
        this.inquiry = inquiry;
        this.inquiryProblem = inquiryProblem;
        this.standardRequest = standardRequest;
        this.title = title;
        this.detail = detail;
        this.workType = workType;
        this.responsibleSystem = responsibleSystem;
        this.assignee = assignee;
    }

    public String getTicketNumber() {
        return ticketNumber;
    }

    public Inquiry getInquiry() {
        return inquiry;
    }

    public InquiryProblem getInquiryProblem() {
        return inquiryProblem;
    }

    public StandardRequest getStandardRequest() {
        return standardRequest;
    }

    public String getTitle() {
        return title;
    }

    public String getDetail() {
        return detail;
    }

    public TicketWorkType getWorkType() {
        return workType;
    }

    public TicketStatus getTicketStatus() {
        return ticketStatus;
    }

    public ItSystem getResponsibleSystem() {
        return responsibleSystem;
    }

    public Member getAssignee() {
        return assignee;
    }

    public Instant getConfirmRequestedAt() {
        return confirmRequestedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public int getVersion() {
        return version;
    }
}
