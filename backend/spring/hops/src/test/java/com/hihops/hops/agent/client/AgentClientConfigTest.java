package com.hihops.hops.agent.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpServer;

/**
 * Python AI 서버 호출용 RestClient가 설정한 주소·시간 제한으로 동작하는지 확인한다.
 * 실제 Python 서버 대신 테스트 안에서 작은 가짜 HTTP 서버를 띄운다.
 */
@SpringBootTest
@ActiveProfiles({"db", "test"})
class AgentClientConfigTest {

    private static final HttpServer fakeAgentServer = startFakeAgentServer();

    @Autowired
    @Qualifier(AgentClientConfig.AGENT_REST_CLIENT)
    private RestClient agentRestClient;

    @DynamicPropertySource
    static void agentProperties(DynamicPropertyRegistry registry) {
        registry.add("hops.agent.base-url", () -> "http://localhost:" + fakeAgentServer.getAddress().getPort());
        registry.add("hops.agent.read-timeout", () -> "500ms"); // 테스트에서는 짧게
    }

    @AfterAll
    static void stopServer() {
        fakeAgentServer.stop(0);
    }

    @Test
    void 설정한_주소의_AI_서버로_요청한다() {
        String body = agentRestClient.get().uri("/ping").retrieve().body(String.class);

        assertThat(body).isEqualTo("pong");
    }

    @Test
    void AI_서버_응답이_시간_제한을_넘으면_기다리지_않고_실패한다() {
        assertThatThrownBy(() -> agentRestClient.get().uri("/slow").retrieve().body(String.class))
                .isInstanceOf(ResourceAccessException.class);
    }

    private static HttpServer startFakeAgentServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/ping", exchange -> respond(exchange, "pong"));
            server.createContext("/slow", exchange -> {
                sleep(2_000); // 시간 제한(500ms)보다 오래 걸리는 응답
                respond(exchange, "late");
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
