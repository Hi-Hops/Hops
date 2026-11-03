package com.hihops.hops.global.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

/**
 * Bearer JWT 검증을 확인한다. 로그인(발급) 기능이 아직 없으므로 테스트 안에서 토큰을 직접 만든다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"db", "test"})
@Import({JwtAuthenticationTest.TestJwtController.class, JwtAuthenticationTest.MethodSecurity.class})
class JwtAuthenticationTest {

    private static final String SECRET = "hops-local-dev-only-secret-change-me-32bytes+"; // application.properties와 같은 값

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 올바른_토큰이면_통과하고_사번을_꺼낼_수_있다() throws Exception {
        mockMvc.perform(get("/api/v2/test/jwt/me").header(HttpHeaders.AUTHORIZATION, bearer(token(SECRET, "hops", "hops-api", 60))))
                .andExpect(status().isOk())
                .andExpect(content().string("E1001"));
    }

    @Test
    void 다른_키로_서명한_토큰은_401() throws Exception {
        String forged = token("someone-else-secret-key-at-least-32-bytes!", "hops", "hops-api", 60);
        expectUnauthorized(forged);
    }

    @Test
    void 만료된_토큰은_401_ACCESS_TOKEN_EXPIRED() throws Exception {
        // WIRE_CONTRACT 12.3: 프론트는 이 코드를 받았을 때만 refresh 1회 후 같은 요청을 다시 보낸다.
        expectUnauthorized(token(SECRET, "hops", "hops-api", -120), "ACCESS_TOKEN_EXPIRED"); // 2분 전 만료 (여유 30초 초과)
    }

    @Test
    void 만료됐어도_다른_키로_서명한_토큰은_만료가_아니라_UNAUTHENTICATED() throws Exception {
        // 서명이 맞는 토큰만 만료를 따진다. 위조 토큰에 "만료"라고 답하면 refresh를 부추기게 된다.
        expectUnauthorized(token("someone-else-secret-key-at-least-32-bytes!", "hops", "hops-api", -120));
    }

    @Test
    void 만료됐어도_발급자가_다르면_UNAUTHENTICATED() throws Exception {
        expectUnauthorized(token(SECRET, "other", "hops-api", -120));
    }

    @Test
    void 만료됐어도_대상이_다르면_UNAUTHENTICATED() throws Exception {
        expectUnauthorized(token(SECRET, "hops", "other-api", -120));
    }

    @Test
    void 발급자가_다르면_401() throws Exception {
        expectUnauthorized(token(SECRET, "other", "hops-api", 60));
    }

    @Test
    void 대상이_다르면_401() throws Exception {
        expectUnauthorized(token(SECRET, "hops", "other-api", 60));
    }

    @Test
    void 형식이_깨진_토큰은_401() throws Exception {
        expectUnauthorized("not-a-jwt");
    }

    @Test
    void roles_클레임이_권한으로_바뀐다() throws Exception {
        String agentToken = bearer(token(SECRET, "hops", "hops-api", 60));
        mockMvc.perform(get("/api/v2/test/jwt/agent-only").header(HttpHeaders.AUTHORIZATION, agentToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v2/test/jwt/admin-only").header(HttpHeaders.AUTHORIZATION, agentToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    private void expectUnauthorized(String token) throws Exception {
        expectUnauthorized(token, "UNAUTHENTICATED");
    }

    private void expectUnauthorized(String token, String code) throws Exception {
        mockMvc.perform(get("/api/v2/test/jwt/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(code));
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    /** docs/API.md §2.5 클레임 모양의 테스트 토큰. expiresInSeconds가 음수면 이미 만료된 토큰이다. */
    private static String token(String secret, String issuer, String audience, long expiresInSeconds) {
        var key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject("E1001")
                .issuer(issuer)
                .audience(List.of(audience))
                .issuedAt(now.minusSeconds(300))
                .expiresAt(now.plusSeconds(expiresInSeconds))
                .claim("employeeNo", "E1001")
                .claim("departmentCode", "DEV")
                .claim("roles", List.of("AGENT"))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurity {
    }

    @RestController
    static class TestJwtController {

        @GetMapping("/test/jwt/me")
        String me(@AuthenticationPrincipal Jwt jwt) {
            return jwt.getClaimAsString("employeeNo");
        }

        @GetMapping("/test/jwt/agent-only")
        @PreAuthorize("hasRole('AGENT')")
        String agentOnly() {
            return "ok";
        }

        @GetMapping("/test/jwt/admin-only")
        @PreAuthorize("hasRole('ADMIN')")
        String adminOnly() {
            return "ok";
        }
    }
}
