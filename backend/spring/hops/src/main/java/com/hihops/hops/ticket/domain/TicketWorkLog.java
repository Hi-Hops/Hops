package com.hihops.hops.ticket.domain;

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
 * 티켓 처리 기록. 담당자가 한 조치·실패한 시도·해결·기능 구현을 추가만 해서 남긴다
 * (docs/ERD.md "티켓 처리 기록 ticket_work_log").
 *
 * <p>고칠 내용은 기존 행을 바꾸지 않고 새 기록으로 남긴다. 해결 사례는 만들어질 때의 처리 기록 ID를 가리켜
 * 어느 기록에서 나왔는지 고정한다.
 *
 * <p>confirmedCause(확인된 원인)는 담당자가 근거로 확인한 경우에만 채운다. 확인하지 못했으면 null로 두고 AI가 채우지 않는다.
 */
@Entity
@Table(name = "ticket_work_log")
public class TicketWorkLog extends CreatedOnlyEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false)
    private Ticket ticket;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_member_id", nullable = false)
    private Member author;

    @Enumerated(EnumType.STRING)
    @Column(name = "log_type", nullable = false, length = 30)
    private WorkLogType logType;

    @Column(name = "action_detail", nullable = false, columnDefinition = "TEXT")
    private String actionDetail;

    @Column(name = "confirmed_cause", columnDefinition = "TEXT")
    private String confirmedCause;

    @Column(name = "verification_result", columnDefinition = "TEXT")
    private String verificationResult;

    @Column(name = "applied_version", length = 100)
    private String appliedVersion;

    protected TicketWorkLog() {
    }

    public TicketWorkLog(Ticket ticket, Member author, WorkLogType logType, String actionDetail, String confirmedCause,
            String verificationResult, String appliedVersion) {
        this.ticket = ticket;
        this.author = author;
        this.logType = logType;
        this.actionDetail = actionDetail;
        this.confirmedCause = confirmedCause;
        this.verificationResult = verificationResult;
        this.appliedVersion = appliedVersion;
    }

    public Ticket getTicket() {
        return ticket;
    }

    public Member getAuthor() {
        return author;
    }

    public WorkLogType getLogType() {
        return logType;
    }

    public String getActionDetail() {
        return actionDetail;
    }

    public String getConfirmedCause() {
        return confirmedCause;
    }

    public String getVerificationResult() {
        return verificationResult;
    }

    public String getAppliedVersion() {
        return appliedVersion;
    }
}
