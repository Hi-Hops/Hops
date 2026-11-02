package com.hihops.hops.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        when(member.getId()).thenReturn(UUID.randomUUID());
        service = new RefreshTokenService(redis, clock);
    }

    @Test
    void 회전하고_10초가_지난_Refresh를_다시_쓰면_탈취로_보고_세션을_폐기한다() {
        // 예전 값을 누군가 훔쳐 쓴 것으로 본다. 그 로그인 세션 전체를 끊어 정상 사용자의 최신 Refresh도 막는다.
        IssuedRefreshToken first = service.issue(member);
        IssuedRefreshToken latest = service.rotate(first.value()).token();

        clock.advance(Duration.ofSeconds(11));

        assertRevoked(first.value());
        assertRevoked(latest.value());
    }

    @Test
    void 회전_직후_10초_안에_예전_Refresh가_오면_경합으로_보고_그_요청만_거절한다() {
        // 탭 두 개가 같은 쿠키로 거의 동시에 갱신한 경우다. 진 요청만 401이고 세션은 유지한다 (Q-BE-2 팀 합의 2026-10-28).
        IssuedRefreshToken first = service.issue(member);
        IssuedRefreshToken latest = service.rotate(first.value()).token();

        clock.advance(Duration.ofSeconds(10));

        assertRevoked(first.value());
        assertThat(service.rotate(latest.value()).memberId()).isEqualTo(member.getId());
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
        IssuedRefreshToken latest = service.rotate(first.value()).token();

        assertThat(service.revoke(first.value())).contains(member.getId());

        assertRevoked(latest.value());
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
