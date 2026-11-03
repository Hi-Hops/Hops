package com.hihops.hops.global.security;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

/**
 * JWT를 어떻게 발급하고 검사할지 정한다 (docs/API.md §2.5).
 *
 * <p>발급({@link #jwtEncoder})과 검증({@link #jwtDecoder})은 같은 비밀키·알고리즘을 쓴다.
 * 도장(비밀키) 하나로 출입증을 찍고, 같은 도장으로 진짜인지 확인한다고 생각하면 된다.
 *
 * <p>검사 순서: 서명(HS256만 허용) → 만료(exp) → 발급자(iss) → 대상(aud).
 * 하나라도 틀리면 401 공통 오류가 된다. 토큰을 단순히 열어 읽는 것(디코딩)은 인증으로 치지 않는다.
 *
 * <p>통과하면 Controller에서 {@code @AuthenticationPrincipal Jwt jwt}로 사번·부서 등 클레임을 꺼내 쓴다.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    /** 발급·검증 모두 이 알고리즘만 쓴다. 발급 쪽(auth)에서도 같은 값을 쓰도록 공개한다. */
    public static final MacAlgorithm ALGORITHM = MacAlgorithm.HS256;

    /** 역할 목록을 담는 클레임 이름. 발급(AccessTokenIssuer)과 권한 변환(아래 converter)이 같은 이름을 써야 권한 검사가 동작한다. */
    public static final String ROLES_CLAIM = "roles";

    // 만료 검사 실패에 붙이는 표식. 401 응답을 만들 때 이 표식만 있으면 ACCESS_TOKEN_EXPIRED로 알린다 (SecurityErrorResponder).
    private static final String EXPIRED_ERROR_CODE = "hops_access_token_expired";

    /** 로그인 성공 시 액세스 토큰을 서명한다. 실제 클레임 구성은 auth/service/AccessTokenIssuer가 한다. */
    @Bean
    JwtEncoder jwtEncoder(JwtProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey(properties)));
    }

    @Bean
    JwtDecoder jwtDecoder(JwtProperties properties, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey(properties))
                .macAlgorithm(ALGORITHM) // 다른 알고리즘(none 등)으로 서명된 토큰은 받지 않는다
                .build();

        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                expiryMarked(timestampValidator(properties, clock), clock),
                new JwtIssuerValidator(properties.issuer()),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        aud -> aud != null && aud.contains(properties.audience())));
        decoder.setJwtValidator(validator);
        return decoder;
    }

    /**
     * 토큰의 {@code roles} 클레임(예: ["BIZ_ADMIN"])을 Spring 권한(ROLE_BIZ_ADMIN)으로 바꾼다.
     * 그래서 {@code hasRole("BIZ_ADMIN")}으로 검사할 수 있다. 로그인한 사용자의 이름(principal name)은 sub(회원 UUID)다.
     */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(ROLES_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /**
     * 실패 원인이 "만료" 하나뿐인지 (WIRE_CONTRACT 12.3 ACCESS_TOKEN_EXPIRED).
     * 검증기는 서명이 맞는 토큰에만 돌기 때문에, 위조 토큰은 여기까지 오지 않고 UNAUTHENTICATED가 된다.
     * 발급자·대상도 틀리면 만료로 보지 않는다(갱신해도 소용없는 토큰이다).
     */
    public static boolean isOnlyExpired(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof JwtValidationException invalid) {
                Collection<OAuth2Error> errors = invalid.getErrors();
                return !errors.isEmpty()
                        && errors.stream().allMatch(error -> EXPIRED_ERROR_CODE.equals(error.getErrorCode()));
            }
        }
        return false;
    }

    // 시각 검사가 실패했고 exp가 지났으면 만료 표식을 붙인다. 아직 유효 전(nbf)인 토큰 같은 다른 시각 오류는 그대로 둔다.
    // 시각 검사와 만료 표식이 같은 시계(ClockConfig)를 본다. 시계가 다르면 검사는 실패했는데 표식이 안 붙을 수 있다.
    private static OAuth2TokenValidator<Jwt> expiryMarked(JwtTimestampValidator timestamps, Clock clock) {
        return jwt -> {
            OAuth2TokenValidatorResult result = timestamps.validate(jwt);
            Instant expiresAt = jwt.getExpiresAt();
            if (result.hasErrors() && expiresAt != null && clock.instant().isAfter(expiresAt)) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error(EXPIRED_ERROR_CODE, "Access Token 만료", null));
            }
            return result;
        };
    }

    private static JwtTimestampValidator timestampValidator(JwtProperties properties, Clock clock) {
        JwtTimestampValidator validator = new JwtTimestampValidator(properties.clockSkew());
        validator.setClock(clock);
        return validator;
    }

    // 발급·검증이 같은 키를 쓰도록 한 곳에서 만든다.
    static SecretKey secretKey(JwtProperties properties) {
        return new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), ALGORITHM.getName()); // HS256 → "HmacSHA256"
    }
}
