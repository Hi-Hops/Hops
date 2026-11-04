package com.hihops.hops.agent.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 서비스 Bearer로 고정 경로만 구독한다. EOF·누락·오류의 복구 정본은 snapshot이다. */
@Component
public class AgentRunEventClient {
    private static final int MAX_FRAME = 65_536;
    private final RestClient client;
    private final JsonMapper mapper;
    private final String token;

    public AgentRunEventClient(@Qualifier(AgentClientConfig.AGENT_SSE_CLIENT) RestClient client,
            JsonMapper mapper, @Value("${AI_SERVICE_TOKEN:}") String token) {
        this.client = client;
        this.mapper = mapper;
        this.token = token;
    }

    public void read(String runId, Consumer<InputStream> opened, Predicate<JsonNode> consumer) {
        if (token.isBlank()) return;
        client.get().uri("/internal/v2/runs/{id}/events", runId)
                .headers(headers -> headers.setBearerAuth(token)).accept(MediaType.TEXT_EVENT_STREAM)
                .exchange((request, response) -> {
                    if (response.getStatusCode().value() != 200
                            || response.getHeaders().getContentType() == null
                            || !MediaType.TEXT_EVENT_STREAM.isCompatibleWith(response.getHeaders().getContentType())) {
                        return null;
                    }
                    try (InputStream body = response.getBody()) {
                        opened.accept(body);
                        parse(new InputStreamReader(body, StandardCharsets.UTF_8), runId, consumer);
                    }
                    return null;
                });
    }

    // UTF-8·CRLF·여러 data 줄·comment를 지원하고 프레임 크기를 읽는 도중 제한한다.
    void parse(Reader reader, String runId, Predicate<JsonNode> consumer) throws IOException {
        StringBuilder line = new StringBuilder();
        StringBuilder data = new StringBuilder();
        String name = "", id = "";
        int size = 0;
        long lastSeq = 0;
        boolean skipLf = false;
        for (int ch; (ch = reader.read()) != -1;) {
            if (Thread.currentThread().isInterrupted()) return;
            if (skipLf && ch == '\n') { skipLf = false; continue; }
            skipLf = false;
            if (++size > MAX_FRAME) throw new IOException("AI 이벤트 크기 초과");
            if (ch != '\n' && ch != '\r') { line.append((char) ch); continue; }
            // CR, LF, CRLF를 모두 한 줄 종료로 처리한다.
            skipLf = ch == '\r';
            String value = line.toString();
            line.setLength(0);
            if (value.isEmpty()) {
                if (!data.isEmpty()) {
                    JsonNode event;
                    try { event = mapper.readTree(data.toString()); }
                    catch (RuntimeException invalid) { throw new IOException("AI 이벤트 JSON 오류"); }
                    JsonNode seq = event.path("seq");
                    if (!"hops-system-1".equals(event.path("contractVersion").asString(""))
                            || !runId.equals(event.path("runId").asString(""))
                            || !seq.isIntegralNumber() || !seq.canConvertToInt() || seq.asInt() <= 0
                            || !name.equals(event.path("eventType").asString(""))
                            || !id.equals(seq.asString()) || !event.path("payload").isObject()
                            || !java.util.Set.of("progress", "result", "answer.delta").contains(name)) {
                        throw new IOException("AI 이벤트 계약 오류");
                    }
                    if (seq.asLong() > lastSeq) {
                        lastSeq = seq.asLong();
                        if (!consumer.test(event)) return;
                    }
                }
                data.setLength(0); name = ""; id = ""; size = 0;
            } else if (!value.startsWith(":")) {
                int colon = value.indexOf(':');
                String field = colon < 0 ? value : value.substring(0, colon);
                String content = colon < 0 ? "" : value.substring(colon + 1);
                if (content.startsWith(" ")) content = content.substring(1);
                switch (field) {
                    case "data" -> { if (!data.isEmpty()) data.append('\n'); data.append(content); }
                    case "event" -> name = content;
                    case "id" -> id = content;
                    default -> { }
                }
            }
        }
    }
}
