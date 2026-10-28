package com.hihops.hops.realtime.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;

import com.hihops.hops.global.security.JwtConfig;
import com.hihops.hops.global.security.JwtProperties;

/**
 * 실제 Tomcat·JWT 필터·hops_test DB를 통과하는 HTTP 스트림 검사.
 * HTTP 서버는 별도 스레드로 DB를 읽으므로 테스트 트랜잭션 대신 고유 fixture를 만들고 직접 정리한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"hops.realtime.heartbeat=100ms", "hops.realtime.timeout=10s"})
@ActiveProfiles({"db", "test"})
class RealtimeHttpIntegrationTest {
    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtEncoder encoder;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private RealtimeService realtime;

    private final UUID departmentId = UUID.randomUUID();
    private final UUID otherDepartmentId = UUID.randomUUID();
    private final UUID memberId = UUID.randomUUID();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void createFixtures() {
        for (UUID id : List.of(departmentId, otherDepartmentId)) {
            jdbc.update("INSERT INTO department (id, code, name, is_routable) VALUES (?, ?, ?, true)",
                    id, "SSE_" + id.toString().substring(0, 20), "SSE 테스트 부서");
        }
        // 로그인 비밀번호를 사용하지 않는 JWT/권한 테스트 fixture다. 실제 로그인 가능한 계정이 아니다.
        jdbc.update("INSERT INTO member (id, login_id, employee_no, password_hash, name, department_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                memberId, memberId.toString(), memberId.toString(), "unused-test-hash", "SSE 테스트 회원", departmentId);
    }

    @AfterEach
    void cleanup() {
        realtime.shutdown();
        jdbc.update("DELETE FROM member_role WHERE member_id = ?", memberId);
        jdbc.update("DELETE FROM member WHERE id = ?", memberId);
        jdbc.update("DELETE FROM department WHERE id IN (?, ?)", departmentId, otherDepartmentId);
        client.close();
    }

    @Test
    void 실제_필터가_미인증과_타부서_연결을_거부한다() throws Exception {
        HttpResponse<String> anonymous = client.send(request(departmentId, null, null),
                HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.body()).contains("UNAUTHENTICATED");
        HttpResponse<String> denied = client.send(request(otherDepartmentId, token(30), null),
                HttpResponse.BodyHandlers.ofString());
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(denied.body()).contains("ACCESS_DENIED");
    }

    @Test
    void 연결과_heartbeat를_받고_커서_종료_후_새로_연결한다() throws Exception {
        String token = token(30);
        try (InputStream stream = connect(token, null)) {
            assertThat(block(stream)).isEqualTo(":connected\n\n");
            assertThat(block(stream)).isEqualTo(":heartbeat\n\n");
        }
        try (InputStream stream = connect(token, UUID.randomUUID().toString())) {
            String wire = block(stream);
            assertThat(wire).contains("event:resync.required");
            String id = wire.lines().filter(line -> line.startsWith("id:")).findFirst().orElseThrow().substring(3);
            String data = wire.lines().filter(line -> line.startsWith("data:")).findFirst().orElseThrow().substring(5);
            var json = new tools.jackson.databind.ObjectMapper().readTree(data);
            assertThat(json.get("eventId").asString()).isEqualTo(id);
            assertThat(UUID.fromString(id).toString()).isEqualTo(id);
            assertThat(block(stream)).isEmpty();
        }
        try (InputStream stream = connect(token, null)) {
            assertThat(block(stream)).isEqualTo(":connected\n\n");
        }
    }

    @Test
    void 열린_HTTP_스트림은_토큰_만료에_종료된다() throws Exception {
        // 수명만 짧은 테스트 JWT를 실제 encoder로 서명한다. 운영의 15분 정책은 변경하지 않는다.
        try (InputStream stream = connect(token(2), null)) {
            assertThat(block(stream)).isEqualTo(":connected\n\n");
            assertThat(rest(stream)).doesNotContain("event:state.changed");
        }
    }

    @Test
    void 회원_차단을_DB에서_확인하면_열린_스트림을_종료한다() throws Exception {
        try (InputStream stream = connect(token(30), null)) {
            assertThat(block(stream)).isEqualTo(":connected\n\n");
            jdbc.update("UPDATE member SET deleted_at = now() WHERE id = ?", memberId);
            assertThat(rest(stream)).doesNotContain("event:state.changed");
        }
    }

    @Test
    void 부서가_바뀐_토큰의_연결은_401_문맥_변경으로_거부한다() throws Exception {
        String token = token(30);
        jdbc.update("UPDATE member SET department_id = ? WHERE id = ?", otherDepartmentId, memberId);
        assertContextChanged(token);
    }

    @Test
    void 역할이_바뀐_토큰의_연결은_401_문맥_변경으로_거부한다() throws Exception {
        String token = token(30);
        jdbc.update("INSERT INTO member_role (member_id, role) VALUES (?, 'BIZ_ADMIN')", memberId);
        assertContextChanged(token);
    }

    private void assertContextChanged(String token) throws Exception {
        HttpResponse<String> response = client.send(request(departmentId, token, null),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        assertThat(response.body()).contains("AUTH_CONTEXT_CHANGED");
    }

    private HttpRequest request(UUID target, String token, String cursor) {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + "/api/realtime/events?targetType=DEPARTMENT_TICKETS&targetId=" + target))
                .timeout(Duration.ofSeconds(5)).header("Accept", "text/event-stream");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (cursor != null) {
            builder.header("Last-Event-ID", cursor);
        }
        return builder.GET().build();
    }

    private InputStream connect(String token, String cursor) throws Exception {
        HttpResponse<InputStream> response = client.send(request(departmentId, token, cursor),
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
        }
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        return response.body();
    }

    private String token(int seconds) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().subject(memberId.toString())
                .issuer(jwtProperties.issuer()).audience(List.of(jwtProperties.audience()))
                .issuedAt(now).expiresAt(now.plusSeconds(seconds))
                .claim("departmentId", departmentId.toString()).claim("roles", List.of()).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(JwtConfig.ALGORITHM).build(), claims))
                .getTokenValue();
    }

    /** 본문 읽기에도 상한을 두어 종료 처리 오류로 테스트가 무한 대기하지 않게 한다. */
    private String block(InputStream stream) throws Exception {
        return CompletableFuture.supplyAsync(() -> {
            try {
                StringBuilder text = new StringBuilder();
                int value;
                while ((value = stream.read()) != -1) {
                    text.append((char) value);
                    if (text.toString().endsWith("\n\n")) {
                        break;
                    }
                }
                return text.toString();
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
        }).get(5, TimeUnit.SECONDS);
    }

    private String rest(InputStream stream) throws Exception {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
        }).get(5, TimeUnit.SECONDS);
    }
}
