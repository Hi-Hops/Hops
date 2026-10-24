package com.hihops.hops.global.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 검증에 쓰는 값. application.properties의 {@code hops.jwt.*}에서 읽는다.
 *
 * <p>비유: JWT는 Spring이 도장을 찍어 준 출입증이다. {@code secret}은 그 도장이고,
 * Spring만 발급·검증하므로(팀 결정) 도장을 다른 서버와 나눌 필요가 없다.
 *
 * @param secret    서명 비밀키. HS256은 32바이트 이상이어야 한다. git에 올라가는 값은 개발용이다.
 * @param issuer    발급자(iss). 이 값이 아닌 토큰은 거부한다.
 * @param audience  대상(aud). 이 서버용으로 발급된 토큰만 받는다.
 * @param clockSkew 만료 시각 판정 여유 시간
 */
@ConfigurationProperties(prefix = "hops.jwt")
public record JwtProperties(String secret, String issuer, String audience, Duration clockSkew) {

    private static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            // 짧은 키는 쉽게 풀리므로 서버를 아예 띄우지 않는다.
            throw new IllegalArgumentException("hops.jwt.secret은 32바이트 이상이어야 합니다.");
        }
        clockSkew = clockSkew == null ? Duration.ofSeconds(30) : clockSkew;
    }
}
