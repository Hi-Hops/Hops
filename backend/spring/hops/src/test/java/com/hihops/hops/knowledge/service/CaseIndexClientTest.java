package com.hihops.hops.knowledge.service;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class CaseIndexClientTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private MockRestServiceServer server;
    private CaseIndexClient client;
    private ObjectNode request;
    @BeforeEach void setup() {
        var builder = RestClient.builder().baseUrl("http://ai.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new CaseIndexClient(builder.build(), mapper, "synthetic-token");
        request = mapper.createObjectNode().put("contractVersion", "hops-system-1").put("jobId", UUID.randomUUID().toString())
                .put("caseId", UUID.randomUUID().toString()).put("caseVersion", 2).put("store", "VECTOR").put("attempt", 1)
                .put("contentDigest", "a".repeat(64));
        request.putObject("approvedCase").put("title", "가상 사례");
    }
    private ObjectNode snapshot() {
        var response = request.deepCopy(); response.remove("approvedCase");
        return response.put("state", "SUCCEEDED").putNull("error").put("updatedAt", Instant.now().toString());
    }
    @Test void 서비스인증으로_고정경로에_접수한다() {
        server.expect(requestTo("http://ai.test/internal/v2/index-jobs")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer synthetic-token"))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).body(snapshot().toString()));
        assertThat(client.send(request, false).snapshot().path("state").asString()).isEqualTo("SUCCEEDED"); server.verify();
    }
    @ParameterizedTest @ValueSource(strings = {"caseId", "caseVersion", "jobId", "attempt", "store", "contentDigest", "contractVersion"})
    void 버전과_저장소와_시도가_다르면_응답을_거부한다(String field) {
        var response = snapshot(); response.put(field, "mismatch");
        assertThat(CaseIndexClient.valid(request, response)).isFalse();
    }
    @Test void 유효한_NOT_FOUND만_재전달을_허용한다() {
        server.expect(requestTo("http://ai.test/internal/v2/index-jobs/" + request.path("jobId").asString() + "?attempt=1"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"NOT_FOUND\",\"message\":\"가상 작업 없음\",\"retryable\":false}"));
        assertThat(client.send(request, true).notFound()).isTrue(); server.verify();
    }
    @Test void HTML404는_재접수_근거가_아니다() {
        server.expect(anything()).andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.TEXT_HTML).body("<h1>404</h1>"));
        assertThatThrownBy(() -> client.send(request, true)).isInstanceOf(RuntimeException.class); server.verify();
    }
    @Test void 빈_오류문구의404는_재접수_근거가_아니다() {
        server.expect(anything()).andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"NOT_FOUND\",\"message\":\"\",\"retryable\":false}"));
        assertThatThrownBy(() -> client.send(request, true)).isInstanceOf(RuntimeException.class); server.verify();
    }
    @Test void 실패상태는_오류가_필수이고_성공상태는_오류가_없어야한다() {
        assertThat(CaseIndexClient.valid(request, snapshot().put("state", "FAILED"))).isFalse();
        var failed = snapshot().put("state", "FAILED"); failed.putObject("error").put("code", "ERROR").put("message", "가상 오류").put("retryable", true);
        assertThat(CaseIndexClient.valid(request, failed)).isTrue();
        failed.put("state", "SUCCEEDED"); assertThat(CaseIndexClient.valid(request, failed)).isFalse();
    }
    @Test void canonical_digest는_키순서와_한글과_배열순서를_계약대로_처리한다() {
        var json = new CaseJson(mapper);
        assertThat(json.canonical(mapper.readTree("{\"z\":1,\"a\":{\"한글\":[\"나\",\"가\"],\"b\":null}}")))
                .isEqualTo("{\"a\":{\"b\":null,\"한글\":[\"나\",\"가\"]},\"z\":1}");
        assertThat(json.digest(mapper.readTree("{\"b\":2,\"a\":1}"))).isEqualTo("43258cff783fe7036d8a43033f830adfc60ec037382473548ac742b888292777");
    }
}
