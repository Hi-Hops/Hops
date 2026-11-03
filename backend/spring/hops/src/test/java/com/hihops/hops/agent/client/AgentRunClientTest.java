package com.hihops.hops.agent.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.json.JsonMapper;

class AgentRunClientTest {
    @Test void 고정_내부경로와_서비스토큰으로_접수하고_응답식별자를_검증한다() {
        var builder = RestClient.builder().baseUrl("http://agent.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        var mapper = JsonMapper.builder().build();
        var client = new AgentRunClient(builder.build(), mapper, "synthetic-service-token");
        var id = "73000000-0000-7000-8000-000000000001";
        server.expect(requestTo("http://agent.test/internal/v2/runs"))
                .andExpect(header("Authorization", "Bearer synthetic-service-token"))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"contractVersion\":\"hops-system-1\",\"runId\":\"" + id + "\",\"status\":\"QUEUED\",\"eventsPath\":\"/internal/v2/runs/" + id + "/events\"}"));
        client.submit(mapper.createObjectNode().put("runId", id));
        server.verify();
    }

    @Test void 다른실행의_접수응답은_성공으로_처리하지_않는다() {
        var builder = RestClient.builder().baseUrl("http://agent.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        var mapper = JsonMapper.builder().build();
        var client = new AgentRunClient(builder.build(), mapper, "synthetic-service-token");
        server.expect(requestTo("http://agent.test/internal/v2/runs"))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"contractVersion\":\"hops-system-1\",\"runId\":\"wrong\",\"status\":\"QUEUED\"}"));
        assertThatThrownBy(() -> client.submit(mapper.createObjectNode().put("runId", "requested")))
                .isInstanceOf(IllegalStateException.class);
        server.verify();
    }
}
