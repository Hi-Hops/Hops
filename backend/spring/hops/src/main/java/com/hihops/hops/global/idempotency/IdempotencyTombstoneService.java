package com.hihops.hops.global.idempotency;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;

/**
 * 보존 기간(expiresAt)이 지난 중복 요청 방지 기록의 응답 본문을 비운다 (docs/ERD.md 6.1절 idempotency_key, WIRE_CONTRACT 12.1).
 *
 * <p>행은 지우지 않는다. 키·해시·operation·resource 표식이 남아 있어야 같은 키로 업무가 다시 실행되지 않고,
 * 실행 오류의 requestId 찾기({@link IdempotencyService#findRequestIdOf})도 계속 동작한다.
 * 만료된 기록의 재요청은 정리 전에도 410이라 응답 동작은 바뀌지 않는다.
 *
 * <p>잠금을 오래 잡지 않게 {@link #BATCH_SIZE}행씩 각자 트랜잭션으로 나눠 처리한다.
 * 주기 실행은 {@link IdempotencyTombstoneScheduler}가 한다.
 */
@Service
@RequiredArgsConstructor
public class IdempotencyTombstoneService {

    static final int BATCH_SIZE = 1000;

    private static final Logger log = LoggerFactory.getLogger(IdempotencyTombstoneService.class);

    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /** @return 이번에 정리한 행 수 */
    public int tombstoneExpired() {
        Instant now = clock.instant();
        int total = 0;
        int batch;
        do {
            batch = Objects.requireNonNull(transactionTemplate.execute(
                    status -> idempotencyRecordRepository.tombstoneExpired(now, BATCH_SIZE)));
            total += batch;
        } while (batch == BATCH_SIZE);
        if (total > 0) {
            log.info("만료된 중복 요청 기록 응답 정리 count={}", total);
        }
        return total;
    }
}
