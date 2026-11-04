package com.hihops.hops.knowledge.service;

import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import com.hihops.hops.agent.client.AgentClientConfig;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 고정 서비스 경로만 호출한다. 유효한 NOT_FOUND만 같은 요청의 재접수를 허용한다. */
@Component
public class CaseIndexClient {
    public record Response(boolean notFound, JsonNode snapshot) {}
    private final RestClient client;
    private final JsonMapper mapper;
    private final String token;
    public CaseIndexClient(@Qualifier(AgentClientConfig.AGENT_REST_CLIENT) RestClient client, JsonMapper mapper,
            @Value("${AI_SERVICE_TOKEN:}") String token) {
        this.client = client; this.mapper = mapper; this.token = token;
    }
    public Response send(JsonNode request, boolean check) {
        return exchange(request, check, null, null);
    }
    public Response submit(JsonNode request, java.util.UUID documentVersionId, String documentDigest) {
        return exchange(request, false, documentVersionId, documentDigest);
    }
    private Response exchange(JsonNode request, boolean check, java.util.UUID documentVersionId, String documentDigest) {
        if (token.isBlank()) throw new IllegalStateException("AI 서비스 인증 미설정");
        RestClient.RequestHeadersSpec<?> call = check
                ? client.get().uri("/internal/v2/index-jobs/{id}?attempt={attempt}", request.path("jobId").asString(), request.path("attempt").asInt())
                : client.post().uri("/internal/v2/index-jobs").contentType(MediaType.APPLICATION_JSON).body(request);
        if (!check && "VECTOR".equals(request.path("store").asString()) && documentVersionId != null) {
            call.header("X-Hops-Document-Version", documentVersionId.toString()).header("X-Hops-Document-Digest", documentDigest);
        }
        return call.header("Authorization", "Bearer " + token).exchange((http, response) -> {
            var raw = response.bodyTo(String.class);
            var node = mapper.readTree(raw == null ? "null" : raw);
            int status = response.getStatusCode().value();
            if (check && status == 404 && node != null && "NOT_FOUND".equals(node.path("code").asString())
                    && node.path("message").isString() && !node.path("message").asString().isBlank()
                    && node.path("retryable").isBoolean()) return new Response(true, null);
            if (status != (check ? 200 : 202) || !valid(request, node)) throw new IllegalStateException("색인 응답 계약 불일치");
            return new Response(false, node);
        });
    }
    static boolean valid(JsonNode request, JsonNode response) {
        if (response == null || !response.isObject()) return false;
        for (var field : List.of("contractVersion", "jobId", "caseId", "caseVersion", "store", "attempt", "contentDigest")) {
            if (!request.path(field).equals(response.path(field))) return false;
        }
        String state = response.path("state").asString();
        if (!List.of("PENDING", "RUNNING", "SUCCEEDED", "FAILED", "UNKNOWN").contains(state)) return false;
        var error = response.path("error");
        if (error.isMissingNode() || "SUCCEEDED".equals(state) && !error.isNull() || "FAILED".equals(state) && !error.isObject()) return false;
        if (!error.isNull() && (!error.path("code").isString() || !error.path("message").isString() || !error.path("retryable").isBoolean())) return false;
        try { java.time.Instant.parse(response.path("updatedAt").asString()); }
        catch (RuntimeException exception) { return false; }
        return true;
    }
}
