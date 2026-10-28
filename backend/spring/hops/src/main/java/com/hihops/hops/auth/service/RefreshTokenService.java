package com.hihops.hops.auth.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

import org.springframework.stereotype.Service;

import com.hihops.hops.user.domain.Member;

/**
 * Refresh Token(Access 재발급 교환권)을 만든다.
 *
 * <p>Access와 달리 JWT가 아니라 아무 뜻 없는 무작위 문자열이다. 내용을 담을 필요가 없고,
 * 서버가 보관한 값과 맞는지만 확인하면 되기 때문이다(복권 번호처럼 맞히기만 어려우면 된다).
 */
@Service
public class RefreshTokenService {

    /** 최초 로그인부터 절대 7일. 갱신해도 늘어나지 않는다 (WIRE_CONTRACT 10절). */
    public static final Duration LIFETIME = Duration.ofDays(7);

    private static final int TOKEN_BYTES = 32; // 256비트: 추측으로 맞힐 수 없는 길이

    private final SecureRandom random = new SecureRandom();

    public IssuedRefreshToken issue(Member member) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        // URL-safe Base64: 쿠키 값에 넣어도 깨지는 문자(+, /, =)가 없다.
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new IssuedRefreshToken(value, LIFETIME);
    }

    /** 발급한 Refresh 원문과 남은 수명. 원문이 로그에 찍히지 않도록 toString에서 숨긴다. */
    public record IssuedRefreshToken(String value, Duration maxAge) {

        @Override
        public String toString() {
            return "IssuedRefreshToken[maxAge=" + maxAge + "]";
        }
    }
}
