package com.hihops.hops.ticket.controller;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.*;
import com.hihops.hops.global.security.JwtConfig;
import com.hihops.hops.global.security.JwtProperties;

/** 실제 Tomcat·JWT 필터·PostgreSQL을 통과하는 사례 조회·편집 HTTP 검사. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CaseHttpIntegrationTest extends TicketCommandFixture {
    @LocalServerPort int port;
    @Autowired JwtEncoder encoder;
    @Autowired JwtProperties properties;

    private String token(UUID actor) {
        var now = Instant.now();
        var claims = JwtClaimsSet.builder().subject(actor.toString()).issuer(properties.issuer())
                .audience(List.of(properties.audience())).issuedAt(now).expiresAt(now.plusSeconds(300))
                .claim("departmentId", department.toString()).claim("roles", List.of()).claim("policyRevision", 1)
                .claim("primarySystemIds", jdbc.queryForList("SELECT id::text FROM it_system WHERE primary_owner_member_id=?", String.class, actor)).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(JwtConfig.ALGORITHM).build(), claims)).getTokenValue();
    }
    @Test void 실제_HTTP에서_초안편집과_멱등성과_인가를_확인한다() throws Exception {
        result(command(staff, "/tickets/" + ticket + "/work-records", work("VERIFIED_RESOLUTION")));
        var record = jdbc.queryForObject("SELECT id FROM ticket_work_log WHERE ticket_id=?", UUID.class, ticket);
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO resolved_case(id,case_number,source_work_log_id,it_system_id,case_type,title,problem_statement,action_detail,verification_result,access_level,origin_type) "
                + "VALUES (?,?,?,?,'RESOLUTION','HTTP 초안','문제','조치','검증','STAFF','SYNTHETIC')", id, "C-" + id.toString().substring(0, 12), record, system);
        var uri = URI.create("http://localhost:" + port + "/api/v2/cases/" + id);
        try (var client = HttpClient.newHttpClient()) {
            assertThat(client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
            assertThat(client.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + token(other)).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
            var get = client.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + token(staff)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(get.statusCode()).isEqualTo(200);
            var body = mapper.createObjectNode().put("requestId", UUID.randomUUID().toString()).put("expectedCaseVersion", 1)
                    .put("title", "HTTP 수정").put("problemStatement", "확인한 문제").put("action", "검토한 조치").put("verification", "검증")
                    .put("applicability", "조건").putNull("appliesToSystemVersion").put("accessScope", "SYSTEM_STAFF");
            body.putArray("limitations");
            var request = HttpRequest.newBuilder(URI.create(uri + "/versions")).header("Authorization", "Bearer " + token(staff))
                    .header("Content-Type", "application/json").header("Idempotency-Key", body.path("requestId").asString())
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            var first = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(first.statusCode()).isEqualTo(200);
            assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).body()).isEqualTo(first.body());
            assertThat(jdbc.queryForObject("SELECT version_no FROM resolved_case WHERE id=?", Integer.class, id)).isEqualTo(2);
        }
    }
}
