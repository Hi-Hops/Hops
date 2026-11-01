package com.hihops.hops.inquiry.domain;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.hihops.hops.global.entity.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 대화 메시지. 문의 안의 사용자·AI 메시지를 순서대로 저장한다 (docs/ERD.md "대화 메시지 message").
 *
 * <p>messageStatus로 답변 생성 중·완료·실패를 화면에 구분해 보여준다.
 *
 * <p>사용자가 메시지를 정정하면 content를 새 내용으로 바꾸고, 바꾸기 전 원문은 메시지 수정 이력(message_revision)에 남긴다.
 */
@Entity
@Table(name = "message",
        uniqueConstraints = @UniqueConstraint(name = "uk_message_inquiry_sequence", columnNames = {"inquiry_id", "sequence_no"}))
public class Message extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inquiry_id", nullable = false)
    private Inquiry inquiry;

    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_type", nullable = false, length = 30)
    private SenderType senderType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    // JSONB 컬럼. 내부 구조가 아직 정해지지 않아 JSON 문자열 그대로 둔다. 구조가 확정되면 전용 클래스로 바꾼다.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_status", nullable = false, length = 30)
    private MessageStatus messageStatus;

    protected Message() {
    }

    public Message(Inquiry inquiry, int sequenceNo, SenderType senderType, String content, String payload,
            MessageStatus messageStatus) {
        this.inquiry = inquiry;
        this.sequenceNo = sequenceNo;
        this.senderType = senderType;
        this.content = content;
        this.payload = payload;
        this.messageStatus = messageStatus;
    }

    public Inquiry getInquiry() {
        return inquiry;
    }

    public int getSequenceNo() {
        return sequenceNo;
    }

    public SenderType getSenderType() {
        return senderType;
    }

    public String getContent() {
        return content;
    }

    public String getPayload() {
        return payload;
    }

    public MessageStatus getMessageStatus() {
        return messageStatus;
    }
}
