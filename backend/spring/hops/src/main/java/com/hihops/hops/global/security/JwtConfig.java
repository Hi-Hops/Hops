package com.hihops.hops.global.security;

import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
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

    /** 로그인 성공 시 액세스 토큰을 서명한다. 실제 클레임 구성은 auth/service/AccessTokenIssuer가 한다. */
    @Bean
    JwtEncoder jwtEncoder(JwtProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey(properties)));
    }

    @Bean
    JwtDecoder jwtDecoder(JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey(properties))
                .macAlgorithm(ALGORITHM) // 다른 알고리즘(none 등)으로 서명된 토큰은 받지 않는다
                .build();

        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(properties.clockSkew()),
                new JwtIssuerValidator(properties.issuer()),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        aud -> aud != null && aud.contains(properties.audience())));
        decoder.setJwtValidator(validator);
        return decoder;
    }

    /** 토큰의 {@code roles} 클레임(예: ["AGENT"])을 Spring 권한(ROLE_AGENT)으로 바꾼다. 권한 이름은 사번(sub)이다. */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    static SecretKey secretKey(JwtProperties properties) {
        return new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
