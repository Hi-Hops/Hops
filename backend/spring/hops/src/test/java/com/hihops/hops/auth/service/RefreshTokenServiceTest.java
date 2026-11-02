package com.hihops.hops.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.hihops.hops.auth.service.RefreshTokenService.IssuedRefreshToken;
import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.user.domain.Member;

/**
 * Refresh 회전과 재사용 검출 (WIRE_CONTRACT 10절 "회전·재사용 검출", QUESTIONS Q-BE-2).
 *
 * <p>Redis는 테스트 전용 15번 DB를 실제로 쓴다. 시간은 {@link TestClock}으로 앞으로 돌려 "10초 뒤"를 흉내 낸다.
 */
@SpringBootTest
@ActiveProfiles({"db", "test"})
class RefreshTokenServiceTest {

    @Autowired
    private StringRedisTemplate redis;

    private final TestClock clock = new TestClock(Instant.parse("2026-11-02T00:00:00Z"));
    private final Member member = mock(Member.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        when(member.getId()).thenReturn(UUID.randomUUID());
        service = new RefreshTokenService(redis, clock, events);
    }

    @Test
    void 회전하고_10초가_지난_Refresh를_다시_쓰면_탈취로_보고_세션을_폐기한다() {
        // 예전 값을 누군가 훔쳐 쓴 것으로 본다. 그 로그인 세션 전체를 끊어 정상 사용자의 최신 Refresh도 막는다.
        IssuedRefreshToken first = service.issue(member);
        IssuedRefreshToken latest = service.rotate(first.value());

        clock.advance(Duration.ofSeconds(11));

        assertRevoked(first.value());
        assertRevoked(latest.value());
        // 로그아웃과 같이 그 회원의 열린 SSE도 닫게 알린다 (RealtimeSessionListener)
        verify(events).publishEvent(new LoginSessionRevokedEvent(member.getId()));
    }

    @Test
    void 회전_직후_10초_안에_예전_Refresh가_오면_경합으로_보고_그_요청만_거절한다() {
        // 탭 두 개가 같은 쿠키로 거의 동시에 갱신한 경우다. 진 요청만 401이고 세션은 유지한다 (Q-BE-2 팀 합의 2026-10-28).
        IssuedRefreshToken first = service.issue(member);
        IssuedRefreshToken latest = service.rotate(first.value());

        clock.advance(Duration.ofSeconds(10));

        assertRevoked(first.value());
        assertThat(service.ownerOf(latest.value())).isEqualTo(member.getId());
        assertThat(service.rotate(latest.value()).value()).isNotBlank();
        verify(events, never()).publishEvent(new LoginSessionRevokedEvent(member.getId()));
    }

    @Test
    void 최초_로그인부터_7일이_지나면_Redis에_값이_남아_있어도_갱신하지_않는다() {
        // 서버 시계와 Redis 만료가 몇 초 어긋나도 7일 기한을 넘겨 새 Refresh를 내주지 않는다 (WIRE_CONTRACT 10절)
        IssuedRefreshToken first = service.issue(member);

        clock.advance(RefreshTokenService.LIFETIME.plusSeconds(1));

        assertRevoked(first.value());
    }

    @Test
    void 갱신과_로그아웃이_겹쳐_예전_Refresh로_로그아웃해도_세션을_끝낸다() {
        // 갱신 요청이 먼저 처리되어 쿠키 값이 바뀐 직후에 로그아웃이 도착한 경우. 로그아웃이 조용히 무시되면 세션이 살아남는다.
        IssuedRefreshToken first = service.issue(member);
        IssuedRefreshToken latest = service.rotate(first.value());

        assertThat(service.revoke(first.value())).contains(member.getId());

        assertRevoked(latest.value());
    }

    @Test
    void 회원_확인용으로_읽기만_하면_Refresh를_소비하지_않는다() {
        // 갱신은 회원 확인(DB)을 먼저 하고 회전은 나중에 한다. DB 조회가 실패해도 쿠키의 Refresh로 다시 시도할 수 있어야 한다.
        IssuedRefreshToken first = service.issue(member);

        assertThat(service.ownerOf(first.value())).isEqualTo(member.getId());
        assertThat(service.ownerOf(first.value())).isEqualTo(member.getId());
        assertThat(service.rotate(first.value()).value()).isNotBlank();
    }

    @Test
    void 같은_Refresh로_동시에_갱신하면_하나만_성공한다() throws Exception {
        // 탭 여러 개가 같은 쿠키로 동시에 갱신하는 경우. 소비(CONSUME 스크립트)가 원자적이어야 새 Refresh가 하나만 생긴다.
        IssuedRefreshToken first = service.issue(member);
        int requests = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        try {
            List<Future<Optional<IssuedRefreshToken>>> results = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                Callable<Optional<IssuedRefreshToken>> rotate = () -> {
                    start.await();
                    try {
                        return Optional.of(service.rotate(first.value()));
                    } catch (BusinessException exception) {
                        return Optional.empty();
                    }
                };
                results.add(pool.submit(rotate));
            }
            start.countDown(); // 모든 스레드를 한꺼번에 출발시킨다

            List<IssuedRefreshToken> winners = new ArrayList<>();
            for (Future<Optional<IssuedRefreshToken>> result : results) {
                result.get(10, TimeUnit.SECONDS).ifPresent(winners::add); // Redis가 멈춰도 테스트가 무한 대기하지 않게
            }
            assertThat(winners).hasSize(1);
            // 진 요청은 10초 유예 안의 경합이다: 세션을 끝내지 않아 이긴 쪽의 새 Refresh로 계속 갱신할 수 있다.
            verify(events, never()).publishEvent(new LoginSessionRevokedEvent(member.getId()));
            assertThat(service.rotate(winners.get(0).value()).value()).isNotBlank();
        } finally {
            pool.shutdownNow();
        }
    }

    private void assertRevoked(String refreshToken) {
        assertThatThrownBy(() -> service.rotate(refreshToken))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.REFRESH_TOKEN_REVOKED);
    }

    /** 테스트에서 시간을 원하는 만큼 앞으로 돌릴 수 있는 시계. */
    static final class TestClock extends Clock {
        private Instant now;

        TestClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
