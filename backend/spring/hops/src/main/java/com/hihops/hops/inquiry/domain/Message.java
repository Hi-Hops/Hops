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
 * <p>저장이 확정된 최종 메시지만 둔다. AI 답변은 실행 접수 때 예약한 answerMessageId를 이 행의 id로 써서 완성 후 한 번 저장하며,
 * 신규 계약에서 저장 행의 messageStatus는 COMPLETED다 (docs/ERD.md 6.1절).
 *
 * <p>사용자가 메시지를 정정하면 content를 새 내용으로 바꾸고 revision을 1 올린다. 바꾸기 전 원문은 메시지 수정 이력(message_revision)에 남긴다.
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

    @Column(nullable = false)
    private int revision = 1;

    // 요청 ID·실행 ID·문제 연결·질문 묶음·가이드 같은 서버 관리 메타데이터 (구조는 docs/DATA.md 11.3절).
    // 아직 전용 클래스가 없어 JSON 문자열로 둔다. 비어 있으면 빈 객체로 저장한다 (JPA가 null을 보내 NOT NULL에 걸리지 않게).
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload = "{}";

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
        this.payload = payload == null ? "{}" : payload;
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

    public int getRevision() {
        return revision;
    }

    public String getPayload() {
        return payload;
    }

    public MessageStatus getMessageStatus() {
        return messageStatus;
    }
}
