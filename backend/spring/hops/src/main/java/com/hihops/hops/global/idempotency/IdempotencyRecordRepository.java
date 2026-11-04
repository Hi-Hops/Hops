package com.hihops.hops.global.idempotency;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** idempotency_key 테이블 저장·조회. 기본 save·findById는 JpaRepository가 제공한다. */
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {

    /** 같은 요청자·계약·키로 이미 받은 요청. 중복 불가 조합(uk_idempotency_key_actor_contract_key)과 같다. */
    Optional<IdempotencyRecord> findByActor_IdAndContractVersionAndIdempotencyKey(UUID actorId,
            String contractVersion, String idempotencyKey);

    /** 작업 번호로 찾되 요청자 본인 것만. 남의 작업은 없는 것과 같이 보인다. */
    Optional<IdempotencyRecord> findByOperationIdAndActor_Id(UUID operationId, UUID actorId);

    /** 이 업무 대상을 만든 요청. resource 표식은 보존 기간이 지나도 남는다. */
    Optional<IdempotencyRecord> findFirstByResourceTypeAndResourceId(String resourceType, UUID resourceId);

    /**
     * 보존 기간이 지난 기록의 응답을 비우고 정리 시각을 남긴다. 행과 키·해시·operation·resource 표식은 그대로 둔다.
     *
     * <p>접수 중(PENDING)은 비울 응답이 없고, 늦게 끝난 업무가 결과를 쓰는 것과 겹치지 않게 건드리지 않는다.
     * 다른 트랜잭션이 잡은 행은 기다리지 않고 다음 정리로 넘긴다(SKIP LOCKED).
     *
     * @return 이번에 정리한 행 수 (최대 limit)
     */
    @Modifying
    @Query(value = """
            UPDATE idempotency_key SET response_status = NULL, response_body = NULL, tombstoned_at = :now
            WHERE id IN (
              SELECT id FROM idempotency_key
              WHERE tombstoned_at IS NULL AND expires_at <= :now AND operation_state <> 'PENDING'
              ORDER BY expires_at
              LIMIT :limit
              FOR UPDATE SKIP LOCKED)
            """, nativeQuery = true)
    int tombstoneExpired(Instant now, int limit);
}
