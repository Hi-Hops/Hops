package com.hihops.hops.agent.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** AI 내부 실행 API 호출의 인증·응답 검증·실패 분류를 가짜 서버로 확인한다. DB나 실제 AI 서버는 필요 없다. */
class AgentRunClientTest {

    private static final String TOKEN = "test-service-token-0123456789abcdef";
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final UUID runId = UUID.randomUUID();

    private MockRestServiceServer server;
    private AgentRunClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ai.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AgentRunClient(builder.build(), mapper, TOKEN);
    }

    private JsonNode request() {
        return mapper.readTree("{\"contractVersion\":\"hops-system-1\",\"runId\":\"" + runId + "\",\"kind\":\"INQUIRY_ANALYSIS\"}");
    }

    private String accepted(String status) {
        return "{\"contractVersion\":\"hops-system-1\",\"runId\":\"" + runId + "\",\"status\":\"" + status
                + "\",\"eventsPath\":\"/internal/v2/runs/" + runId + "/events\"}";
    }

    private static String error(String code, boolean retryable) {
        return "{\"code\":\"" + code + "\",\"message\":\"x\",\"retryable\":" + retryable + "}";
    }

    @Test
    void 접수는_서비스_토큰으로_실행_입력_그대로_보낸다() {
        server.expect(requestTo("http://ai.test/internal/v2/runs"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json(request().toString(), JsonCompareMode.STRICT))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).body(accepted("QUEUED")));

        client.submit(request());

        server.verify();
    }

    @Test
    void 접수_응답의_실행_ID가_다르면_계약_오류다() {
        String other = accepted("QUEUED").replace(runId.toString(), UUID.randomUUID().toString());
        server.expect(requestTo("http://ai.test/internal/v2/runs"))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).body(other));

        assertThatThrownBy(() -> client.submit(request())).isInstanceOfSatisfying(AgentRunClientException.class, e -> {
            assertThat(e.code()).isEqualTo(AgentRunClientException.UPSTREAM_PROTOCOL_ERROR);
            assertThat(e.retryable()).isFalse();
        });
    }

    @Test
    void 접수_응답이_202가_아니면_계약_오류다() {
        server.expect(requestTo("http://ai.test/internal/v2/runs"))
                .andRespond(withSuccess(accepted("QUEUED"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.submit(request())).isInstanceOfSatisfying(AgentRunClientException.class,
                e -> assertThat(e.code()).isEqualTo(AgentRunClientException.UPSTREAM_PROTOCOL_ERROR));
    }

    @Test
    void 접수_응답의_상태가_QUEUED가_아니면_계약_오류다() {
        server.expect(requestTo("http://ai.test/internal/v2/runs"))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).body(accepted("RUNNING")));

        assertThatThrownBy(() -> client.submit(request())).isInstanceOfSatisfying(AgentRunClientException.class,
                e -> assertThat(e.code()).isEqualTo(AgentRunClientException.UPSTREAM_PROTOCOL_ERROR));
    }

    @Test
    void 한도_초과_429는_재시도_가능과_Retry_After를_전달한다() {
        server.expect(requestTo("http://ai.test/internal/v2/runs"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                        .header("Retry-After", "7").body(error("RATE_LIMITED", true)));

        assertThatThrownBy(() -> client.submit(request())).isInstanceOfSatisfying(AgentRunClientException.class, e -> {
            assertThat(e.code()).isEqualTo("RATE_LIMITED");
            assertThat(e.retryable()).isTrue();
            assertThat(e.httpStatus()).isEqualTo(429);
            assertThat(e.retryAfterSeconds()).isEqualTo(7);
        });
    }

    @Test
    void 같은_실행_ID에_다른_입력이면_409_충돌을_재시도_불가로_전달한다() {
        server.expect(requestTo("http://ai.test/internal/v2/runs"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON).body(error("CONFLICT", false)));

        assertThatThrownBy(() -> client.submit(request())).isInstanceOfSatisfying(AgentRunClientException.class, e -> {
            assertThat(e.code()).isEqualTo("CONFLICT");
            assertThat(e.retryable()).isFalse();
        });
    }

    @Test
    void AI가_준비되지_않은_503은_재시도_가능으로_전달한다() {
        server.expect(requestTo("http://ai.test/internal/v2/runs"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
                        .body(error("SERVICE_UNAVAILABLE", true)));

        assertThatThrownBy(() -> client.submit(request())).isInstanceOfSatisfying(AgentRunClientException.class, e -> {
            assertThat(e.code()).isEqualTo("SERVICE_UNAVAILABLE");
            assertThat(e.retryable()).isTrue();
        });
    }

    @Test
    void 오류_본문이_계약_형식이_아니면_계약_오류다() {
        server.expect(requestTo("http://ai.test/internal/v2/runs"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY).contentType(MediaType.TEXT_HTML).body("<html>bad gateway</html>"));

        assertThatThrownBy(() -> client.submit(request())).isInstanceOfSatisfying(AgentRunClientException.class, e -> {
            assertThat(e.code()).isEqualTo(AgentRunClientException.UPSTREAM_PROTOCOL_ERROR);
            assertThat(e.httpStatus()).isEqualTo(502);
        });
    }

    @Test
    void 연결이_안_되면_재시도_가능한_서비스_불가로_바꾼다() {
        server.expect(requestTo("http://ai.test/internal/v2/runs"))
                .andRespond(request -> {
                    throw new SocketTimeoutException("read timed out");
                });

        assertThatThrownBy(() -> client.submit(request())).isInstanceOfSatisfying(AgentRunClientException.class, e -> {
            assertThat(e.code()).isEqualTo(AgentRunClientException.SERVICE_UNAVAILABLE);
            assertThat(e.retryable()).isTrue();
            assertThat(e.httpStatus()).isZero();
        });
    }

    @Test
    void 상태_조회는_서비스_토큰으로_고정_경로를_호출하고_본문을_돌려준다() {
        String snapshot = "{\"contractVersion\":\"hops-system-1\",\"runId\":\"" + runId
                + "\",\"status\":\"RUNNING\",\"lastSeq\":0,\"result\":null,\"error\":null}";
        server.expect(requestTo("http://ai.test/internal/v2/runs/" + runId))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andRespond(withSuccess(snapshot, MediaType.APPLICATION_JSON));

        JsonNode result = client.snapshot(runId.toString());

        assertThat(result.path("status").asString()).isEqualTo("RUNNING");
        server.verify();
    }

    @Test
    void 상태_조회_응답의_실행_ID가_다르면_계약_오류다() {
        String snapshot = "{\"contractVersion\":\"hops-system-1\",\"runId\":\"" + UUID.randomUUID()
                + "\",\"status\":\"RUNNING\",\"lastSeq\":0,\"result\":null,\"error\":null}";
        server.expect(requestTo("http://ai.test/internal/v2/runs/" + runId))
                .andRespond(withSuccess(snapshot, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.snapshot(runId.toString())).isInstanceOfSatisfying(AgentRunClientException.class,
                e -> assertThat(e.code()).isEqualTo(AgentRunClientException.UPSTREAM_PROTOCOL_ERROR));
    }

    @Test
    void 없는_실행_404는_재시도_불가_NOT_FOUND로_전달한다() {
        server.expect(requestTo("http://ai.test/internal/v2/runs/" + runId))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body(error("NOT_FOUND", false)));

        assertThatThrownBy(() -> client.snapshot(runId.toString())).isInstanceOfSatisfying(AgentRunClientException.class, e -> {
            assertThat(e.code()).isEqualTo("NOT_FOUND");
            assertThat(e.retryable()).isFalse();
        });
    }

    @Test
    void 서비스_토큰이_없으면_요청을_보내지_않는다() {
        AgentRunClient unauthenticated = new AgentRunClient(RestClient.builder().baseUrl("http://ai.test").build(), mapper, " ");

        assertThatThrownBy(() -> unauthenticated.submit(request())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> unauthenticated.snapshot(runId.toString())).isInstanceOf(IllegalStateException.class);
        // 서버에는 어떤 요청도 가지 않았다
        server.verify();
    }

    @Test
    void IOException은_연결_실패로_분류된다() {
        server.expect(requestTo("http://ai.test/internal/v2/runs/" + runId))
                .andRespond(request -> {
                    throw new IOException("connection refused");
                });

        assertThatThrownBy(() -> client.snapshot(runId.toString())).isInstanceOfSatisfying(AgentRunClientException.class,
                e -> assertThat(e.code()).isEqualTo(AgentRunClientException.SERVICE_UNAVAILABLE));
    }

    // ---------- 승인 사례 색인 (contracts/agents I1·I1.1) ----------

    private final UUID jobId = UUID.randomUUID();
    private final UUID indexCaseId = UUID.randomUUID();

    private JsonNode indexRequest() {
        return mapper.readTree("{\"contractVersion\":\"hops-system-1\",\"jobId\":\"" + jobId
                + "\",\"caseId\":\"" + indexCaseId + "\",\"caseVersion\":1,\"store\":\"VECTOR\",\"attempt\":1,"
                + "\"contentDigest\":\"" + "a".repeat(64) + "\",\"approvedCase\":{}}");
    }

    private String indexSnapshot(String state) {
        return "{\"contractVersion\":\"hops-system-1\",\"jobId\":\"" + jobId + "\",\"caseId\":\"" + indexCaseId
                + "\",\"caseVersion\":1,\"store\":\"VECTOR\",\"attempt\":1,\"state\":\"" + state
                + "\",\"contentDigest\":\"" + "a".repeat(64) + "\",\"error\":null,\"updatedAt\":\"2026-11-05T00:00:00Z\"}";
    }

    @Test
    void 색인_접수는_서비스_토큰으로_요청_그대로_고정_경로에_보내고_202_snapshot을_돌려준다() {
        server.expect(requestTo("http://ai.test/internal/v2/index-jobs"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json(indexRequest().toString(), JsonCompareMode.STRICT))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).body(indexSnapshot("PENDING")));

        JsonNode result = client.applyIndex(indexRequest());

        assertThat(result.path("state").asString()).isEqualTo("PENDING");
        server.verify();
    }

    @Test
    void 색인_접수_응답의_작업_ID가_다르거나_202가_아니면_계약_오류다() {
        String other = indexSnapshot("PENDING").replace(jobId.toString(), UUID.randomUUID().toString());
        server.expect(requestTo("http://ai.test/internal/v2/index-jobs"))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).body(other));
        assertThatThrownBy(() -> client.applyIndex(indexRequest())).isInstanceOfSatisfying(AgentRunClientException.class,
                e -> assertThat(e.code()).isEqualTo(AgentRunClientException.UPSTREAM_PROTOCOL_ERROR));

        server.reset();
        server.expect(requestTo("http://ai.test/internal/v2/index-jobs"))
                .andRespond(withSuccess(indexSnapshot("PENDING"), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.applyIndex(indexRequest())).isInstanceOfSatisfying(AgentRunClientException.class,
                e -> assertThat(e.code()).isEqualTo(AgentRunClientException.UPSTREAM_PROTOCOL_ERROR));
    }

    @Test
    void 같은_작업에_다른_내용이면_409_충돌을_재시도_불가로_전달한다() {
        server.expect(requestTo("http://ai.test/internal/v2/index-jobs"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON).body(error("CONFLICT", false)));

        assertThatThrownBy(() -> client.applyIndex(indexRequest())).isInstanceOfSatisfying(AgentRunClientException.class, e -> {
            assertThat(e.code()).isEqualTo("CONFLICT");
            assertThat(e.httpStatus()).isEqualTo(409);
            assertThat(e.retryable()).isFalse();
        });
    }

    @Test
    void 색인_조회는_작업_ID와_attempt로_고정_경로를_호출한다() {
        server.expect(requestTo("http://ai.test/internal/v2/index-jobs/" + jobId + "?attempt=2"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andRespond(withSuccess(indexSnapshot("RUNNING"), MediaType.APPLICATION_JSON));

        JsonNode result = client.indexSnapshot(jobId.toString(), 2);

        assertThat(result.path("state").asString()).isEqualTo("RUNNING");
        server.verify();
    }

    @Test
    void 색인_조회_응답의_작업_ID가_다르면_계약_오류다() {
        String other = indexSnapshot("RUNNING").replace(jobId.toString(), UUID.randomUUID().toString());
        server.expect(requestTo("http://ai.test/internal/v2/index-jobs/" + jobId + "?attempt=1"))
                .andRespond(withSuccess(other, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.indexSnapshot(jobId.toString(), 1)).isInstanceOfSatisfying(AgentRunClientException.class,
                e -> assertThat(e.code()).isEqualTo(AgentRunClientException.UPSTREAM_PROTOCOL_ERROR));
    }

    @Test
    void 형식이_유효한_404_NOT_FOUND만_없음으로_전달한다() {
        server.expect(requestTo("http://ai.test/internal/v2/index-jobs/" + jobId + "?attempt=1"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body(error("NOT_FOUND", false)));

        assertThatThrownBy(() -> client.indexSnapshot(jobId.toString(), 1)).isInstanceOfSatisfying(AgentRunClientException.class, e -> {
            assertThat(e.code()).isEqualTo("NOT_FOUND");
            assertThat(e.httpStatus()).isEqualTo(404);
        });
    }

    @Test
    void 형식이_다른_404는_없음이_아니라_계약_오류다() {
        for (String body : new String[] {
                "{\"code\":\"NOT_FOUND\",\"message\":\"x\",\"retryable\":false,\"extra\":1}",
                "{\"code\":\"NOT_FOUND\",\"message\":\" \",\"retryable\":false}",
                "{\"code\":\"NOT_FOUND\",\"message\":\"x\"}",
                "{\"code\":\"CONFLICT\",\"message\":\"x\",\"retryable\":false}",
                "<html>404</html>"}) {
            server.reset();
            server.expect(requestTo("http://ai.test/internal/v2/index-jobs/" + jobId + "?attempt=1"))
                    .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body(body));
            assertThatThrownBy(() -> client.indexSnapshot(jobId.toString(), 1)).as(body)
                    .isInstanceOfSatisfying(AgentRunClientException.class,
                            e -> assertThat(e.code()).isEqualTo(AgentRunClientException.UPSTREAM_PROTOCOL_ERROR));
        }
    }

    @Test
    void 색인_조회의_503과_연결_실패는_서비스_불가로_전달한다() {
        server.expect(requestTo("http://ai.test/internal/v2/index-jobs/" + jobId + "?attempt=1"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
                        .body(error("SERVICE_UNAVAILABLE", true)));
        assertThatThrownBy(() -> client.indexSnapshot(jobId.toString(), 1)).isInstanceOfSatisfying(AgentRunClientException.class,
                e -> assertThat(e.code()).isEqualTo("SERVICE_UNAVAILABLE"));

        server.reset();
        server.expect(requestTo("http://ai.test/internal/v2/index-jobs/" + jobId + "?attempt=1"))
                .andRespond(request -> {
                    throw new SocketTimeoutException("timeout");
                });
        assertThatThrownBy(() -> client.indexSnapshot(jobId.toString(), 1)).isInstanceOfSatisfying(AgentRunClientException.class,
                e -> assertThat(e.code()).isEqualTo(AgentRunClientException.SERVICE_UNAVAILABLE));
    }

    @Test
    void 색인_호출도_서비스_토큰이_없으면_요청을_보내지_않는다() {
        AgentRunClient unauthenticated = new AgentRunClient(RestClient.builder().baseUrl("http://ai.test").build(), mapper, " ");

        assertThatThrownBy(() -> unauthenticated.applyIndex(indexRequest())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> unauthenticated.indexSnapshot(jobId.toString(), 1)).isInstanceOf(IllegalStateException.class);
        server.verify();
    }
}
