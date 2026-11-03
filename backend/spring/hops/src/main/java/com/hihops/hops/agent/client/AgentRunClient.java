package com.hihops.hops.agent.client;

import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring → Python 내부 실행 API 클라이언트 (contracts/agents/README.md "hops-system-1 실행 계약안").
 *
 * <p>사용자 JWT를 전달하지 않고 서비스 Bearer 토큰만 쓴다. 호출 경로는 고정이며 AI 응답이 알려 준 경로
 * (eventsPath 등)를 따라가지 않는다. 응답은 계약(contractVersion·runId·status)과 대조해 다르면 모두 실패로 본다.
 *
 * <p>이 클래스는 전송만 맡는다. 실행 상태 전이·재시도·결과 적용은 호출하는 쪽(AgentRunDispatcher)이 결정한다.
 * 실패는 {@link AgentRunClientException}으로 던지며 호출 측이 재시도 가능 여부를 읽을 수 있다.
 */
@Component
public class AgentRunClient {

    private static final String CONTRACT_VERSION = "hops-system-1";

    private final RestClient client;
    private final JsonMapper mapper;
    private final String token;

    public AgentRunClient(@Qualifier(AgentClientConfig.AGENT_REST_CLIENT) RestClient client, JsonMapper mapper,
            @Value("${AI_SERVICE_TOKEN:}") String token) {
        this.client = client;
        this.mapper = mapper;
        this.token = token;
    }

    /**
     * 실행을 접수시킨다. 같은 runId·같은 입력의 재전송은 AI 서버가 같은 접수 결과를 돌려준다.
     *
     * @param request agent_run.input_snapshot에 고정한 AiRunRequest JSON
     */
    public void submit(JsonNode request) {
        requireToken();
        String runId = request.path("runId").asString("");
        Response response = call(() -> client.post().uri("/internal/v2/runs")
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(request.toString())
                .exchange((req, res) -> new Response(res.getStatusCode().value(),
                        res.getHeaders().getFirst("Retry-After"), res.bodyTo(String.class))));
        JsonNode body = bodyOrThrow(response, 202);
        if (!matches(body, runId) || !"QUEUED".equals(body.path("status").asString(""))) {
            throw protocolError(response.status(), "AI 접수 응답이 계약과 다릅니다");
        }
    }

    /**
     * 저장된 실행 상태를 조회한다. 진행 중이면 result가 없고, 끝났으면 AiResult가 들어 있다.
     *
     * @return AiRunSnapshot JSON (status: QUEUED·RUNNING·SUCCEEDED·FAILED·TIMED_OUT·SUPERSEDED·UNKNOWN)
     */
    public JsonNode snapshot(String runId) {
        requireToken();
        Response response = call(() -> client.get().uri("/internal/v2/runs/{id}", runId)
                .headers(headers -> headers.setBearerAuth(token))
                .exchange((req, res) -> new Response(res.getStatusCode().value(),
                        res.getHeaders().getFirst("Retry-After"), res.bodyTo(String.class))));
        JsonNode body = bodyOrThrow(response, 200);
        if (!matches(body, runId)) {
            throw protocolError(response.status(), "AI 상태 응답이 계약과 다릅니다");
        }
        return body;
    }

    private Response call(Supplier<Response> request) {
        try {
            return request.get();
        } catch (RestClientException exception) {
            // 연결 거부·시간 초과·읽기 실패. 서버가 응답한 오류는 exchange가 던지지 않고 Response로 돌려준다.
            throw new AgentRunClientException(AgentRunClientException.SERVICE_UNAVAILABLE, true, 0, null,
                    "AI 서버에 연결하지 못했습니다", exception);
        }
    }

    private JsonNode bodyOrThrow(Response response, int expectedStatus) {
        JsonNode body = parse(response.body());
        if (response.status() == expectedStatus) {
            if (body == null) {
                throw protocolError(response.status(), "AI 응답 본문을 읽을 수 없습니다");
            }
            return body;
        }
        throw errorOf(response, body);
    }

    // 오류 본문은 AiError {code, message, retryable}. 읽을 수 없으면 계약 위반으로 본다.
    private AgentRunClientException errorOf(Response response, @Nullable JsonNode body) {
        int status = response.status();
        if (body == null || !body.path("code").isString() || !body.path("retryable").isBoolean()) {
            return protocolError(status, "AI 오류 응답이 계약과 다릅니다: HTTP " + status);
        }
        return new AgentRunClientException(body.path("code").asString(), body.path("retryable").asBoolean(), status,
                retryAfterOf(response.retryAfter()), "AI 서버가 요청을 거절했습니다: HTTP " + status, null);
    }

    private static AgentRunClientException protocolError(int status, String message) {
        return new AgentRunClientException(AgentRunClientException.UPSTREAM_PROTOCOL_ERROR, false, status, null, message,
                null);
    }

    private static @Nullable Integer retryAfterOf(@Nullable String header) {
        if (header == null) {
            return null;
        }
        try {
            int seconds = Integer.parseInt(header.trim());
            return seconds >= 0 ? seconds : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private @Nullable JsonNode parse(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(text);
            return node.isObject() ? node : null;
        } catch (JacksonException exception) {
            return null;
        }
    }

    private static boolean matches(JsonNode body, String runId) {
        return CONTRACT_VERSION.equals(body.path("contractVersion").asString(""))
                && !runId.isEmpty() && runId.equals(body.path("runId").asString(""));
    }

    private void requireToken() {
        if (token.isBlank()) {
            throw new IllegalStateException("AI 서비스 인증 토큰(AI_SERVICE_TOKEN)이 설정되지 않았습니다");
        }
    }

    private record Response(int status, @Nullable String retryAfter, @Nullable String body) {
    }
}
