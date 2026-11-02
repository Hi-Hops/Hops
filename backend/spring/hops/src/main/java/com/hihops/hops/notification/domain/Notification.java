package com.hihops.hops.notification.domain;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.hihops.hops.global.entity.CreatedOnlyEntity;
import com.hihops.hops.inquiry.domain.Inquiry;
import com.hihops.hops.ticket.domain.Ticket;
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
 * 알림. 서비스 내부 알림함이다 (docs/ERD.md "알림 notification").
 *
 * <p>배정·해결 확인 요청·결과 통보·사례 승인 요청을 알린다. 문의자 알림은 inquiry, 담당자 알림은 ticket을 가리켜
 * 누르면 그 화면으로 이동한다. 추가만 하는 기록이라 {@link CreatedOnlyEntity}를 상속한다.
 *
 * <p>예약 재알림·무응답 자동 종료가 없어서 알림 시각을 상태 전이 기준으로 쓰지 않는다.
 * 알림을 읽었다고 해결 확인이나 처리 완료로 보지 않는다.
 */
@Entity
@Table(name = "notification")
public class Notification extends CreatedOnlyEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_member_id", nullable = false)
    private Member recipient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inquiry_id")
    private Inquiry inquiry;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id")
    private Ticket ticket;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, length = 30)
    private NotificationType notificationType;

    @Column(nullable = false, length = 200)
    private String title;

    // 번호·요약·링크. 알림 종류마다 내용이 달라 JSON 문자열로 둔다.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    protected Notification() {
    }

    public Notification(Member recipient, Inquiry inquiry, Ticket ticket, NotificationType notificationType,
            String title, String payload) {
        this.recipient = recipient;
        this.inquiry = inquiry;
        this.ticket = ticket;
        this.notificationType = notificationType;
        this.title = title;
        this.payload = payload;
    }

    public Member getRecipient() {
        return recipient;
    }

    public Inquiry getInquiry() {
        return inquiry;
    }

    public Ticket getTicket() {
        return ticket;
    }

    public NotificationType getNotificationType() {
        return notificationType;
    }

    public String getTitle() {
        return title;
    }

    public String getPayload() {
        return payload;
    }
}
