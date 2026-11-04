package com.hihops.hops.global.idempotency;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import lombok.RequiredArgsConstructor;

/**
 * 만료 기록 정리({@link IdempotencyTombstoneService})를 주기적으로 실행한다. 실패하면 로그만 남기고 다음 주기에 다시 한다.
 *
 * <p>설정 (application.properties):
 * <ul>
 *   <li>{@code hops.idempotency.tombstone.enabled}: 끄면 이 설정과 스케줄링 자체가 등록되지 않는다(기본 켜짐, 테스트는 끔)</li>
 *   <li>{@code hops.idempotency.tombstone.initial-delay}·{@code interval}: 시작 뒤 첫 실행까지, 이전 실행이 끝난 뒤 다음 실행까지 (ISO-8601)</li>
 * </ul>
 *
 * <p>SSE용 realtimeScheduler와 섞이지 않게 전용 스레드 하나를 쓴다. 서버가 여러 대여도 정리 쿼리가 행을 나눠 잡아 겹치지 않는다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "hops.idempotency.tombstone.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
class IdempotencyTombstoneScheduler {

    static final String SCHEDULER = "idempotencyTombstoneTaskScheduler";

    private final IdempotencyTombstoneService idempotencyTombstoneService;

    @Bean(SCHEDULER)
    ThreadPoolTaskScheduler idempotencyTombstoneTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("idempotency-tombstone-");
        return scheduler;
    }

    @Scheduled(scheduler = SCHEDULER,
            initialDelayString = "${hops.idempotency.tombstone.initial-delay:PT1M}",
            fixedDelayString = "${hops.idempotency.tombstone.interval:PT1H}")
    void tombstoneExpired() {
        idempotencyTombstoneService.tombstoneExpired();
    }
}
