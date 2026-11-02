package com.hihops.hops.inquiry.domain;

import com.hihops.hops.global.entity.CreatedOnlyEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 메시지 수정 이력. 사용자가 메시지를 고쳤을 때 고치기 전 원문을 남긴다
 * (docs/ERD.md "메시지 수정 이력 message_revision", specs/contracts/DATA_TYPES.md MessageRevision).
 *
 * <p>추가만 하는 이력이라 {@link CreatedOnlyEntity}를 상속한다. 메시지의 현재 차수는 "이력 개수 + 1"로 계산한다.
 */
@Entity
@Table(name = "message_revision",
        uniqueConstraints = @UniqueConstraint(name = "uk_message_revision_message_revision", columnNames = {"message_id", "revision_no"}))
public class MessageRevision extends CreatedOnlyEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    @Column(name = "revision_no", nullable = false)
    private int revisionNo;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    protected MessageRevision() {
    }

    public MessageRevision(Message message, int revisionNo, String content) {
        this.message = message;
        this.revisionNo = revisionNo;
        this.content = content;
    }

    public Message getMessage() {
        return message;
    }

    public int getRevisionNo() {
        return revisionNo;
    }

    public String getContent() {
        return content;
    }
}
