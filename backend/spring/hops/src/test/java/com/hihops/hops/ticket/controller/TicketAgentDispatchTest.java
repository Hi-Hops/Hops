package com.hihops.hops.ticket.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.hihops.hops.agent.client.AgentRunClient;
import com.hihops.hops.knowledge.repository.ResolvedCaseRepository;

import tools.jackson.databind.JsonNode;

@SpringBootTest(properties = "hops.agent.dispatch-enabled=true")
class TicketAgentDispatchTest extends TicketCommandFixture {
    @MockitoBean
    private AgentRunClient client;
    @Autowired
    private ResolvedCaseRepository cases;

    private tools.jackson.databind.node.ObjectNode successfulResult(String runId) {
        var result = mapper.createObjectNode().put("contractVersion", "hops-system-1")
                .put("runId", runId).put("kind", "CASE_DRAFT")
                .put("inquiryId", inquiry.toString()).put("inquiryVersion", 1).put("status", "SUCCEEDED");
        result.putNull("answer"); result.putNull("guide"); result.putNull("error");
        for (var field : java.util.List.of("problemProposals", "questions", "ticketProposals", "evidence")) result.putArray(field);
        return result;
    }

    private JsonNode reusableSnapshot(String runId, JsonNode limitations) {
        var result = successfulResult(runId);
        var proposal = result.putObject("caseProposal");
        proposal.put("reusable", true).put("title", "가상 사례").put("action", "모델 제안 조치")
                .put("verification", "모델 제안 검증").put("problemStatement", "가상 문제")
                .put("applicability", "가상 환경 전용").put("accessScope", "SYSTEM_STAFF");
        proposal.putArray("sourceMessageIds"); proposal.putArray("entityLinks");
        proposal.putNull("appliesToSystemVersion");
        if (limitations != null) proposal.set("limitations", limitations);
        var snapshot = mapper.createObjectNode().put("status", "SUCCEEDED");
        snapshot.set("result", result);
        return snapshot;
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "null", "\"제한\"", "[1]", "[null]"})
    void 제한조건_목록이_잘못되면_사례생성만_실패하고_처리기록은_보존한다(String value) throws Exception {
        when(client.snapshot(any())).thenAnswer(invocation -> reusableSnapshot(
                invocation.getArgument(0, String.class), "missing".equals(value) ? null : json(value)));
        result(command(staff, "/tickets/" + ticket + "/work-records", work("VERIFIED_RESOLUTION")));
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(detail().path("caseGeneration").path("state").asString()).isIn("SUCCEEDED", "FAILED"));
        assertThat(detail().path("caseGeneration").path("state").asString()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT error_code FROM agent_run WHERE ticket_id=?", String.class, ticket)).isEqualTo("INVALID_OUTPUT");
        verify(client).snapshot(any());
        assertThat(detail().path("workRecords").size()).isEqualTo(1);
        assertThat(detail().path("activeResolutionRequest").path("state").asString()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resolved_case WHERE source_work_log_id IN (SELECT id FROM ticket_work_log WHERE ticket_id=?)", Integer.class, ticket)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "[\"관리자 권한 필요\",\"구버전에는 적용 불가\"]"})
    void 사례초안은_원기록과_모델의_제한조건을_누락없이_보존한다(String limitations) throws Exception {
        when(client.snapshot(any())).thenAnswer(invocation -> reusableSnapshot(invocation.getArgument(0, String.class), json(limitations)));
        result(command(staff, "/tickets/" + ticket + "/work-records", work("VERIFIED_RESOLUTION")));
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(detail().path("caseGeneration").path("state").asString()).isEqualTo("SUCCEEDED"));
        assertThat(jdbc.queryForObject("SELECT action_detail FROM resolved_case WHERE source_work_log_id IN (SELECT id FROM ticket_work_log WHERE ticket_id=?)", String.class, ticket)).isEqualTo("실제 조치");
        assertThat(jdbc.queryForObject("SELECT confirmed_cause FROM resolved_case WHERE source_work_log_id IN (SELECT id FROM ticket_work_log WHERE ticket_id=?)", String.class, ticket)).isNull();
        var metadata = json(jdbc.queryForObject("SELECT confirmed_facts::text FROM resolved_case WHERE source_work_log_id IN (SELECT id FROM ticket_work_log WHERE ticket_id=?)", String.class, ticket));
        assertThat(metadata.path("limitations")).isEqualTo(json(limitations));
        var caseId = jdbc.queryForObject("SELECT id FROM resolved_case WHERE source_work_log_id IN (SELECT id FROM ticket_work_log WHERE ticket_id=?)", java.util.UUID.class, ticket);
        var loaded = cases.findById(caseId).orElseThrow();
        assertThat(json(loaded.getConfirmedFacts()).path("limitations")).isEqualTo(json(limitations));
    }

    @Test void commit_뒤에만_AI를_전달하고_재사용할_사례_없음을_별도로_저장한다() throws Exception {
        var submitted = new AtomicReference<JsonNode>();
        doAnswer(invocation -> {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_work_log WHERE ticket_id=?", Integer.class, ticket)).isEqualTo(1);
            submitted.set(invocation.getArgument(0)); return null;
        }).when(client).submit(any());
        when(client.snapshot(any())).thenAnswer(invocation -> {
            var input = submitted.get();
            var result = successfulResult(invocation.getArgument(0, String.class));
            var proposal = result.putObject("caseProposal").put("reusable", false);
            for (var field : java.util.List.of("title", "action", "verification", "problemStatement", "applicability", "accessScope", "appliesToSystemVersion")) proposal.putNull(field);
            for (var field : java.util.List.of("limitations", "sourceMessageIds", "entityLinks")) proposal.putArray(field);
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
        verify(client).submit(any());
    }

    @Test void AI_응답을_기다리는_동안_권한세대가_바뀌면_사례를_저장하지_않는다() throws Exception {
        doAnswer(invocation -> {
            jdbc.update("UPDATE member SET policy_revision=policy_revision+1 WHERE id=?", staff);
            return null;
        }).when(client).submit(any());
        when(client.snapshot(any())).thenAnswer(invocation -> reusableSnapshot(invocation.getArgument(0, String.class), json("[]")));
        result(command(staff, "/tickets/" + ticket + "/work-records", work("VERIFIED_RESOLUTION")));
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT run_status FROM agent_run WHERE ticket_id=?", String.class, ticket)).isEqualTo("FAILED"));
        assertThat(jdbc.queryForObject("SELECT error_code FROM agent_run WHERE ticket_id=?", String.class, ticket)).isEqualTo("AUTH_CONTEXT_INVALID");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_work_log WHERE ticket_id=?", Integer.class, ticket)).isEqualTo(1);
    }
}
