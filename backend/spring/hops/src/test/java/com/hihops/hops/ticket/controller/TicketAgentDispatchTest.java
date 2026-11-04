package com.hihops.hops.ticket.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.UUID;
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void Curator_결과보다_먼저_저장한_해결확인_응답을_초안생성에_반영한다(boolean resolved) throws Exception {
        when(client.snapshot(any())).thenAnswer(invocation -> {
            String confirmation = detail().path("activeResolutionRequest").path("id").asString();
            result(command(requester, "/resolution-requests/" + confirmation + "/responses",
                    mapper.createObjectNode().put("requestId", UUID.randomUUID().toString()).put("expectedRequestRevision", 1)
                            .put("decision", resolved ? "RESOLVED" : "UNRESOLVED")));
            return sourcedSnapshot(invocation.getArgument(0, String.class));
        });
        result(command(staff, "/tickets/" + ticket + "/work-records", work("VERIFIED_RESOLUTION")));
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(detail().path("caseGeneration").path("state").asString()).isEqualTo(resolved ? "SUCCEEDED" : "NO_REUSABLE_CASE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resolved_case WHERE source_work_log_id IN (SELECT id FROM ticket_work_log WHERE ticket_id=?)", Integer.class, ticket))
                .isEqualTo(resolved ? 1 : 0);
        assertThat(detail().path("state").asString()).isEqualTo(resolved ? "RESOLVED" : "IN_PROGRESS");
    }

    @org.junit.jupiter.api.BeforeEach
    void userSource() {
        var payload = mapper.createObjectNode();
        payload.putArray("problemLinks").addObject().put("problemId", problem.toString()).put("revision", 1)
                .putArray("spans").addObject().put("start", 0).put("end", 5);
        jdbc.update("UPDATE message SET sender_type='USER',content='결재 오류',payload=?::jsonb WHERE id=?", payload.toString(), message);
    }

    private JsonNode sourcedSnapshot(String runId) {
        var snapshot = (tools.jackson.databind.node.ObjectNode) reusableSnapshot(runId, json("[]"));
        var proposal = (tools.jackson.databind.node.ObjectNode) snapshot.path("result").path("caseProposal");
        proposal.putArray("sourceMessageIds").add(message.toString());
        proposal.putArray("entityLinks").addObject().put("relation", "APPLIES_TO").put("targetId", system.toString())
                .putArray("evidenceIds").add(message.toString());
        return snapshot;
    }

    @Test void Curator의_USER_출처와_시스템연결을_검증해_초안에_보존한다() throws Exception {
        userSource();
        when(client.snapshot(any())).thenAnswer(invocation -> sourcedSnapshot(invocation.getArgument(0, String.class)));
        result(command(staff, "/tickets/" + ticket + "/work-records", work("VERIFIED_RESOLUTION")));
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(detail().path("caseGeneration").path("state").asString()).isEqualTo("SUCCEEDED"));
        var metadata = json(jdbc.queryForObject("SELECT confirmed_facts::text FROM resolved_case WHERE source_work_log_id IN (SELECT id FROM ticket_work_log WHERE ticket_id=?)", String.class, ticket));
        assertThat(metadata.path("sourceMessageIds")).isEqualTo(mapper.valueToTree(java.util.List.of(message)));
        var links = json(jdbc.queryForObject("SELECT relation_candidates::text FROM resolved_case WHERE source_work_log_id IN (SELECT id FROM ticket_work_log WHERE ticket_id=?)", String.class, ticket));
        assertThat(links.get(0).path("evidenceIds")).isEqualTo(metadata.path("sourceMessageIds"));
        assertThat(detail().path("activeResolutionRequest").path("state").asString()).isEqualTo("PENDING");
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "empty", "duplicate", "not-uuid", "link-unknown", "link-empty", "wrong-system", "broader-scope", "ai-source", "other-problem-source"})
    void 잘못된_출처나_연결은_초안만_거절하고_원업무를_보존한다(String invalid) throws Exception {
        if ("ai-source".equals(invalid)) jdbc.update("UPDATE message SET sender_type='AI' WHERE id=?", message);
        if ("other-problem-source".equals(invalid)) jdbc.update("UPDATE message SET payload='{}'::jsonb WHERE id=?", message);
        when(client.snapshot(any())).thenAnswer(invocation -> {
            var snapshot = sourcedSnapshot(invocation.getArgument(0, String.class));
            var proposal = (tools.jackson.databind.node.ObjectNode) snapshot.path("result").path("caseProposal");
            var link = (tools.jackson.databind.node.ObjectNode) proposal.path("entityLinks").get(0);
            switch (invalid) {
                case "unknown" -> proposal.putArray("sourceMessageIds").add(UUID.randomUUID().toString());
                case "empty" -> { proposal.putArray("sourceMessageIds"); proposal.putArray("entityLinks"); }
                case "duplicate" -> proposal.putArray("sourceMessageIds").add(message.toString()).add(message.toString());
                case "not-uuid" -> proposal.putArray("sourceMessageIds").add("forged-source");
                case "link-unknown" -> link.putArray("evidenceIds").add(UUID.randomUUID().toString());
                case "link-empty" -> link.putArray("evidenceIds");
                case "wrong-system" -> link.put("targetId", otherSystem.toString());
                case "broader-scope" -> proposal.put("accessScope", "EMPLOYEE");
                case "ai-source", "other-problem-source" -> { }
                default -> throw new IllegalArgumentException(invalid);
            }
            return snapshot;
        });
        result(command(staff, "/tickets/" + ticket + "/work-records", work("VERIFIED_RESOLUTION")));
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(detail().path("caseGeneration").path("state").asString()).isEqualTo("FAILED"));
        assertThat(jdbc.queryForObject("SELECT error_code FROM agent_run WHERE ticket_id=?", String.class, ticket)).isEqualTo("INVALID_OUTPUT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resolved_case WHERE source_work_log_id IN (SELECT id FROM ticket_work_log WHERE ticket_id=?)", Integer.class, ticket)).isZero();
        assertThat(detail().path("workRecords").size()).isEqualTo(1);
        assertThat(detail().path("activeResolutionRequest").path("state").asString()).isEqualTo("PENDING");
    }

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
        proposal.putArray("sourceMessageIds").add(message.toString()); proposal.putArray("entityLinks");
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
