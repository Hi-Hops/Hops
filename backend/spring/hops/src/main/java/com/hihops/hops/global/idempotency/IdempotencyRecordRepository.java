package com.hihops.hops.global.idempotency;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** idempotency_key 테이블 저장·조회. 기본 save·findById는 JpaRepository가 제공한다. */
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {
}
