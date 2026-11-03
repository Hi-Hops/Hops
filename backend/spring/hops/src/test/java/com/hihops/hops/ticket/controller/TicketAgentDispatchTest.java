package com.hihops.hops.ticket.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.hihops.hops.agent.client.AgentRunClient;

import tools.jackson.databind.JsonNode;

@SpringBootTest(properties = "hops.agent.dispatch-enabled=true")
class TicketAgentDispatchTest extends TicketCommandFixture {
    @MockitoBean
    private AgentRunClient client;

    @Test void 사례초안의_조치와_검증과_원인은_모델문구가_아닌_원기록에_고정한다() throws Exception {
        var submitted = new AtomicReference<JsonNode>();
        doAnswer(invocation -> { submitted.set(invocation.getArgument(0)); return null; }).when(client).submit(any());
        when(client.snapshot(any())).thenAnswer(invocation -> {
            var input = submitted.get();
            var result = mapper.createObjectNode().put("contractVersion", "hops-system-1")
                    .put("runId", invocation.getArgument(0, String.class)).put("kind", "CASE_DRAFT")
                    .put("inquiryId", inquiry.toString()).put("inquiryVersion", 1).put("status", "SUCCEEDED");
            var proposal = result.putObject("caseProposal");
            proposal.put("reusable", true).put("title", "가상 사례").put("action", "모델 제안 조치")
                    .put("verification", "모델 제안 검증").put("problemStatement", "가상 문제")
                    .put("applicability", "가상 환경 전용").put("accessScope", "SYSTEM_STAFF");
            proposal.putArray("sourceMessageIds"); proposal.putArray("entityLinks"); proposal.putArray("limitations");
            var snapshot = mapper.createObjectNode().put("status", "SUCCEEDED"); snapshot.set("result", result); return snapshot;
        });
        result(command(staff, "/tickets/" + ticket + "/work-records", work("VERIFIED_RESOLUTION")));
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(detail().path("caseGeneration").path("state").asString()).isEqualTo("SUCCEEDED"));
        assertThat(jdbc.queryForObject("SELECT action_detail FROM resolved_case WHERE source_work_log_id IN (SELECT id FROM ticket_work_log WHERE ticket_id=?)", String.class, ticket)).isEqualTo("실제 조치");
        assertThat(jdbc.queryForObject("SELECT confirmed_cause FROM resolved_case WHERE source_work_log_id IN (SELECT id FROM ticket_work_log WHERE ticket_id=?)", String.class, ticket)).isNull();
    }

    @Test void commit_뒤에만_AI를_전달하고_재사용할_사례_없음을_별도로_저장한다() throws Exception {
        var submitted = new AtomicReference<JsonNode>();
        doAnswer(invocation -> {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_work_log WHERE ticket_id=?", Integer.class, ticket)).isEqualTo(1);
            submitted.set(invocation.getArgument(0)); return null;
        }).when(client).submit(any());
        when(client.snapshot(any())).thenAnswer(invocation -> {
            var input = submitted.get();
            var result = mapper.createObjectNode().put("contractVersion", "hops-system-1")
                    .put("runId", invocation.getArgument(0, String.class)).put("kind", "CASE_DRAFT")
                    .put("inquiryId", inquiry.toString()).put("inquiryVersion", 1).put("status", "SUCCEEDED");
            result.putObject("caseProposal").put("reusable", false);
            var snapshot = mapper.createObjectNode().put("status", "SUCCEEDED"); snapshot.set("result", result); return snapshot;
        });
        result(command(staff, "/tickets/" + ticket + "/work-records", work("VERIFIED_RESOLUTION")));
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(detail().path("caseGeneration").path("state").asString()).isEqualTo("NO_REUSABLE_CASE"));
        assertThat(submitted.get().path("problemIds").get(0).asString()).isEqualTo(problem.toString());
        assertThat(submitted.get().path("workRecord").path("resolutionRequestId").asString()).isNotBlank();
        assertThat(submitted.get().path("accessContext").path("purpose").asString()).isEqualTo("CASE_DRAFT");
    }

    @Test void AI_전달실패는_처리기록과_확인요청을_취소하지_않는다() throws Exception {
        doThrow(new IllegalStateException("synthetic failure")).when(client).submit(any());
        result(command(staff, "/tickets/" + ticket + "/work-records", work("VERIFIED_RESOLUTION")));
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(detail().path("caseGeneration").path("state").asString()).isEqualTo("FAILED"));
        assertThat(detail().path("workRecords").size()).isEqualTo(1);
        assertThat(detail().path("activeResolutionRequest").path("state").asString()).isEqualTo("PENDING");
    }
}
