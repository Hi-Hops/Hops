package com.hihops.hops.global.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

/**
 * ERD(docs/ERD_설명.md)의 테이블 대부분이 공통으로 가진 컬럼을 한 곳에 정의한 부모 클래스.
 *
 * <table>
 *   <tr><th>컬럼</th><th>필드</th><th>채워지는 방법</th></tr>
 *   <tr><td>id UUID</td><td>{@link #id}</td><td>저장 시 uuidv7 자동 발급 (시간 순으로 정렬되는 UUID)</td></tr>
 *   <tr><td>created_at</td><td>{@link #createdAt}</td><td>처음 저장할 때 자동, 이후 바뀌지 않음</td></tr>
 *   <tr><td>updated_at</td><td>{@link #updatedAt}</td><td>저장·수정할 때마다 자동</td></tr>
 *   <tr><td>deleted_at</td><td>{@link #deletedAt}</td><td>{@link #markDeleted()} 호출 시. NULL이면 사용 중</td></tr>
 * </table>
 *
 * <p>사용법: 기능 Entity가 이 클래스를 상속하면 위 네 컬럼이 자동으로 붙는다.
 * <pre>
 * &#64;Entity
 * &#64;Table(name = "department")
 * public class Department extends BaseEntity { ... }
 * </pre>
 *
 * <p>삭제: ERD 규칙("실제로 지우지 않고 기록을 남기려고")에 따라 행을 지우지 않고 deleted_at만 기록한다.
 * 조회할 때 삭제된 행을 빼는 조건은 각 Repository에서 정한다.
 *
 * <p>주의: 이력 테이블처럼 updated_at·deleted_at이 없는 테이블(예: ticket_event)은 상속하지 않고 필요한 컬럼만 직접 둔다.
 */
@MappedSuperclass
public abstract class BaseEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** 행을 지우지 않고 삭제 시각만 기록한다. 이미 삭제된 경우 처음 삭제 시각을 유지한다. */
    public void markDeleted() {
        if (deletedAt == null) {
            deletedAt = Instant.now();
        }
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public UUID getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
