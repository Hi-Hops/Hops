package com.hihops.hops.global.idempotency;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
import jakarta.persistence.UniqueConstraint;

/**
 * 중복 요청 방지 기록. 처리한 변경 요청과 그 결과를 남긴다 (docs/ERD.md "중복 요청 방지 기록 idempotency_key", 6.1절).
 *
 * <p>왜 필요한가: 사용자가 메시지를 보냈는데 응답이 네트워크에서 사라지면, 프론트는 같은 Idempotency-Key로 다시 보낸다.
 * 서버는 이 기록을 찾아 "이미 받은 요청"이면 업무를 다시 하지 않고 기존 결과(operationState·응답)를 돌려준다.
 * 같은 키인데 메서드·경로·본문(requestHash)이 다르면 충돌(409)로 거절한다.
 *
 * <p>흐름: 요청을 받으면 PENDING으로 접수하고(응답 없음), 업무 반영과 최종 응답을 한 트랜잭션으로 저장한다.
 * 상세 응답은 최소 24시간(expiresAt) 보존하고, 그 뒤에는 행을 지우지 않고 응답만 비운다(tombstonedAt).
 * 키·해시·operation·resource 표식은 남아 같은 키로 새 업무가 실행되지 않는다.
 *
 * <p>중복 불가는 (요청자, 계약 버전, 키)다. 같은 사람이 같은 계약에서 같은 키를 다른 API에 다시 쓸 수 없다.
 *
 * <p>클래스 이름이 테이블 이름(idempotency_key)과 다른 이유: 컬럼에도 idempotency_key가 있어
 * Entity까지 같은 이름이면 {@code key.getIdempotencyKey()}처럼 헷갈린다. ERD 한글명 "중복 요청 방지 기록"에 맞췄다.
 */
@Entity
@Table(name = "idempotency_key",
        uniqueConstraints = @UniqueConstraint(name = "uk_idempotency_key_actor_contract_key",
                columnNames = {"actor_member_id", "contract_version", "idempotency_key"}))
public class IdempotencyRecord extends CreatedOnlyEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_member_id", nullable = false)
    private Member actor;

    // 어느 계약의 요청인지 (예: hops-system-1)
    @Column(name = "contract_version", nullable = false, length = 50)
    private String contractVersion;

    @Column(name = "http_method", nullable = false, length = 10)
    private String httpMethod;

    @Column(name = "request_path", nullable = false, length = 500)
    private String requestPath;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "operation_id", nullable = false, unique = true)
    private UUID operationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_state", nullable = false, length = 30)
    private OperationState operationState;

    // 만들어지거나 바뀐 업무 대상 (예: INQUIRY + 문의 ID). 접수 단계에는 비어 있다.
    @Column(name = "resource_type", length = 30)
    private String resourceType;

    @Column(name = "resource_id")
    private UUID resourceId;

    // 처음 처리한 HTTP 상태와 응답 JSON. 접수 중이거나 만료 정리 뒤에는 null.
    @Column(name = "response_status")
    private Integer responseStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body", columnDefinition = "jsonb")
    private String responseBody;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "tombstoned_at")
    private Instant tombstonedAt;

    protected IdempotencyRecord() {
    }

    public IdempotencyRecord(Member actor, String contractVersion, String httpMethod, String requestPath,
            String idempotencyKey, String requestHash, UUID operationId, OperationState operationState,
            Instant expiresAt) {
        this.actor = actor;
        this.contractVersion = contractVersion;
        this.httpMethod = httpMethod;
        this.requestPath = requestPath;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.operationId = operationId;
        this.operationState = operationState;
        this.expiresAt = expiresAt;
    }

    /**
     * 업무 반영이 끝나 최종 응답을 기록한다. 접수(PENDING) 기록과 같은 트랜잭션에서 호출한다.
     *
     * @param resource       저장한 업무 대상
     * @param responseStatus 처음 응답한 HTTP 상태
     * @param responseBody   처음 응답한 JSON. 같은 키로 다시 오면 이 본문을 그대로 돌려준다
     */
    public void succeed(ResourceRef resource, int responseStatus, String responseBody) {
        this.operationState = OperationState.SUCCEEDED;
        this.resourceType = resource.type().name();
        this.resourceId = resource.id();
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
    }

    /** 상세 결과 보존 기간이 지났거나 응답을 비웠으면 true. 이때는 결과 대신 410을 돌려준다. */
    public boolean isExpired(Instant now) {
        return tombstonedAt != null || !now.isBefore(expiresAt);
    }

    public Member getActor() {
        return actor;
    }

    public String getContractVersion() {
        return contractVersion;
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

    public UUID getOperationId() {
        return operationId;
    }

    public OperationState getOperationState() {
        return operationState;
    }

    public String getResourceType() {
        return resourceType;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getTombstonedAt() {
        return tombstonedAt;
    }
}
