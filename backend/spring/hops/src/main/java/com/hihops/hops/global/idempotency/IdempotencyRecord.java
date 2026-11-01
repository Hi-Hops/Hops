package com.hihops.hops.global.idempotency;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.hihops.hops.global.entity.CreatedOnlyEntity;
import com.hihops.hops.user.domain.Member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 중복 요청 방지 기록. 처리한 변경 요청과 그 첫 응답을 남긴다 (docs/ERD.md "중복 요청 방지 기록 idempotency_key").
 *
 * <p>왜 필요한가: 사용자가 메시지를 보냈는데 응답이 네트워크에서 사라지면, 프론트는 같은 Idempotency-Key로 다시 보낸다.
 * 서버는 이 기록을 찾아 "이미 처리한 요청"이면 업무를 다시 하지 않고 처음 응답(responseStatus·responseBody)을 그대로 돌려준다.
 * 같은 키인데 본문이 다르면(requestHash 불일치) 충돌로 거절한다.
 *
 * <p>업무 변경과 같은 트랜잭션으로 저장한다. 업무 저장이 롤백되면 이 기록도 남지 않아 같은 키로 처음부터 다시 처리할 수 있다.
 * 같은 키가 동시에 오면 중복 불가 조합(요청자·방식·경로·키) 때문에 한 요청만 저장된다.
 *
 * <p>클래스 이름이 테이블 이름(idempotency_key)과 다른 이유: 컬럼에도 idempotency_key가 있어
 * Entity까지 같은 이름이면 {@code key.getIdempotencyKey()}처럼 헷갈린다. ERD 한글명 "중복 요청 방지 기록"에 맞췄다.
 */
@Entity
@Table(name = "idempotency_key",
        uniqueConstraints = @UniqueConstraint(name = "uk_idempotency_key_request",
                columnNames = {"actor_member_id", "http_method", "request_path", "idempotency_key"}))
public class IdempotencyRecord extends CreatedOnlyEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_member_id", nullable = false)
    private Member actor;

    @Column(name = "http_method", nullable = false, length = 10)
    private String httpMethod;

    @Column(name = "request_path", nullable = false, length = 500)
    private String requestPath;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "response_status", nullable = false)
    private int responseStatus;

    // 처음 처리한 응답 JSON을 그대로 다시 돌려주려고 문자열로 보관한다.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body", nullable = false, columnDefinition = "jsonb")
    private String responseBody;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected IdempotencyRecord() {
    }

    public IdempotencyRecord(Member actor, String httpMethod, String requestPath, String idempotencyKey,
            String requestHash, int responseStatus, String responseBody, Instant expiresAt) {
        this.actor = actor;
        this.httpMethod = httpMethod;
        this.requestPath = requestPath;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.expiresAt = expiresAt;
    }

    public Member getActor() {
        return actor;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public String getRequestPath() {
        return requestPath;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public int getResponseStatus() {
        return responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
