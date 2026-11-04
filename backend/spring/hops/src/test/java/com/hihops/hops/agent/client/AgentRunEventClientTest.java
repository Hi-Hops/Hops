package com.hihops.hops.agent.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AgentRunEventClientTest {
    private final String runId = UUID.randomUUID().toString();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final AgentRunEventClient client = new AgentRunEventClient(RestClient.create(), mapper, "test-token");

    private String frame(int seq, String type) {
        return "id: " + seq + "\nevent: " + type + "\ndata: {\"contractVersion\":\"hops-system-1\","
                + "\"runId\":\"" + runId + "\",\"seq\":" + seq + ",\"eventType\":\"" + type
                + "\",\"payload\":{\"role\":\"INQUIRY\",\"displaySummary\":\"문의 확인 중\"}}\n\n";
    }

    @Test
    void comment와_CRLF와_중복을_처리하고_최종_뒤에는_읽지_않는다() throws Exception {
        List<JsonNode> received = new ArrayList<>();
        String wire = ": heartbeat\n\n" + frame(1, "progress") + frame(1, "progress")
                + frame(3, "result") + frame(4, "progress");
        client.parse(new StringReader(wire.replace("\n", "\r\n")), runId, event -> {
            received.add(event);
            return !"result".equals(event.path("eventType").asString());
        });
        assertThat(received).hasSize(2);
        assertThat(received.getFirst().path("payload").path("displaySummary").asString()).isEqualTo("문의 확인 중");
        assertThat(received.getLast().path("seq").asInt()).isEqualTo(3);
    }

    @Test
    void 여러_data줄을_합친다() throws Exception {
        List<JsonNode> received = new ArrayList<>();
        String wire = frame(1, "progress").replace(",\"payload\"", ",\ndata: \"payload\"");
        client.parse(new StringReader(wire), runId, event -> received.add(event));
        assertThat(received).hasSize(1);
    }

    @Test
    void 다른_run이나_이벤트_ID_불일치와_큰_프레임을_거부한다() {
        for (String wire : List.of(frame(1, "progress").replace(runId, UUID.randomUUID().toString()),
                frame(1, "progress").replace("id: 1", "id: 2"), "data: " + "x".repeat(65_536))) {
            assertThatThrownBy(() -> client.parse(new StringReader(wire), runId, event -> true))
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void 서비스_Bearer와_고정_경로로_스트림을_읽는다() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ai.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://ai.test/internal/v2/runs/" + runId + "/events"))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andRespond(withSuccess(frame(1, "progress"), MediaType.TEXT_EVENT_STREAM));
        List<JsonNode> received = new ArrayList<>();
        new AgentRunEventClient(builder.build(), mapper, "test-token")
                .read(runId, stream -> { }, event -> received.add(event));
        assertThat(received).hasSize(1);
        server.verify();
    }

    @Test
    void 실제_HTTP에서도_응답_종료_전_진행을_읽고_연결을_닫는다() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/internal/v2/runs/" + runId + "/events", exchange -> {
            try {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(frame(1, "progress").getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally { exchange.close(); }
        });
        server.start();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var factory = new JdkClientHttpRequestFactory(HttpClient.newHttpClient());
            factory.setReadTimeout(Duration.ofSeconds(3));
            RestClient rest = RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .requestFactory(factory).build();
            var eventClient = new AgentRunEventClient(rest, mapper, "test-token");
            List<JsonNode> received = new ArrayList<>();
            var task = executor.submit(() -> eventClient.read(runId, stream -> { }, event -> {
                received.add(event);
                return false;
            }));
            task.get(4, TimeUnit.SECONDS);
            assertThat(received).hasSize(1);
            assertThat(release.getCount()).isEqualTo(1);
        } finally {
            release.countDown();
            server.stop(0);
        }
    }
}
