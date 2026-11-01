package com.hihops.hops.global.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

/**
 * 추가만 하는 이력·기록 테이블의 공통 컬럼(id, created_at)을 정의한 부모 클래스.
 *
 * <p>ERD(docs/ERD.md 2. 공통 규칙)의 이력·기록 테이블 9개는 한 번 쓰면 고치거나 지우지 않는다.
 * 그래서 {@link BaseEntity}와 달리 수정일시(updated_at)·삭제일시(deleted_at)가 없다.
 * 고칠 내용이 생기면 행을 바꾸지 않고 새 행을 추가한다.
 *
 * <p>대상: 메시지 수정 이력, 표준 요청서, 티켓 변경 이력, 티켓 처리 기록, AI 실행 단계,
 * 중복 요청 방지 기록, 알림, 문서 버전, 검색 조각.
 *
 * <p>사용법:
 * <pre>
 * &#64;Entity
 * &#64;Table(name = "message_revision")
 * public class MessageRevision extends CreatedOnlyEntity { ... }
 * </pre>
 */
@MappedSuperclass
public abstract class CreatedOnlyEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public UUID getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
