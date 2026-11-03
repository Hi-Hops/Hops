package com.hihops.hops.global.idempotency;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** idempotency_key 테이블 저장·조회. 기본 save·findById는 JpaRepository가 제공한다. */
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {

    /** 같은 요청자·계약·키로 이미 받은 요청. 중복 불가 조합(uk_idempotency_key_actor_contract_key)과 같다. */
    Optional<IdempotencyRecord> findByActor_IdAndContractVersionAndIdempotencyKey(UUID actorId,
            String contractVersion, String idempotencyKey);

    /** 작업 번호로 찾되 요청자 본인 것만. 남의 작업은 없는 것과 같이 보인다. */
    Optional<IdempotencyRecord> findByOperationIdAndActor_Id(UUID operationId, UUID actorId);

    /** 이 업무 대상을 만든 요청. resource 표식은 보존 기간이 지나도 남는다. */
    Optional<IdempotencyRecord> findFirstByResourceTypeAndResourceId(String resourceType, UUID resourceId);
}
