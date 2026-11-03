package com.hihops.hops.agent.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 사용자 JWT나 서버가 준 eventsPath를 재사용하지 않고 고정 내부 API만 호출한다. */
@Component
public class AgentRunClient {
    private final RestClient client;
    private final ObjectMapper mapper;
    private final String token;

    public AgentRunClient(@Qualifier(AgentClientConfig.AGENT_REST_CLIENT) RestClient client, ObjectMapper mapper,
            @Value("${AI_SERVICE_TOKEN:}") String token) {
        this.client = client; this.mapper = mapper; this.token = token;
    }

    public void submit(JsonNode request) {
        requireToken();
        var response = client.post().uri("/internal/v2/runs").headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).body(request.toString()).retrieve().toEntity(String.class);
        var body = mapper.readTree(response.getBody());
        if (response.getStatusCode().value() != 202 || !matches(body, request.path("runId").asString())
                || !"QUEUED".equals(body.path("status").asString())) throw new IllegalStateException("AI 접수 응답 계약 오류");
    }

    public JsonNode snapshot(String id) {
        requireToken();
        var body = mapper.readTree(client.get().uri("/internal/v2/runs/{id}", id)
                .headers(headers -> headers.setBearerAuth(token)).retrieve().body(String.class));
        if (!matches(body, id)) throw new IllegalStateException("AI 상태 응답 계약 오류");
        return body;
    }

    private static boolean matches(JsonNode body, String id) {
        return body != null && "hops-system-1".equals(body.path("contractVersion").asString()) && id.equals(body.path("runId").asString());
    }

    private void requireToken() {
        if (token.isBlank()) throw new IllegalStateException("AI 서비스 인증 미설정");
    }
}
