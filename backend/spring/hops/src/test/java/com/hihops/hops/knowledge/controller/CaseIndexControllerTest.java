package com.hihops.hops.knowledge.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.hihops.hops.agent.client.AgentRunClientException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 사례 색인 전달·확인·재시도 (contracts/agents I1·I1.1, WIRE12 POST /cases/{caseId}/index-checks·index-retries).
 *
 * <p>승인 commit 뒤 저장된 저장소별 요청(source_metadata.indexRequests)을 AI에 전달하고, 담당자가 수동으로 확인·재시도한다.
 * 두 저장소가 같은 승인 버전으로 SUCCEEDED일 때만 공개한다. AI 서버는 {@link CaseDraftFixture}의 가짜 클라이언트로 대신한다.
 */
class CaseIndexControllerTest extends CaseDraftFixture {

    @BeforeEach
    void 가짜_AI_기본_응답() {
        // 접수는 PENDING으로 돌려준다. 결과는 각 테스트가 정한다.
        when(client.applyIndex(any())).thenAnswer(call -> snapshot(call.getArgument(0), "PENDING"));
    }

    // ---------- 승인 뒤 전달 ----------

    @Test
    void 승인하면_저장된_VECTOR_NEO4J_요청이_각각_한_번_AI에_전달된다() throws Exception {
        UUID caseId = approved();

        ArgumentCaptor<JsonNode> sent = ArgumentCaptor.forClass(JsonNode.class);
        verify(client, times(2)).applyIndex(sent.capture());
        assertThat(sent.getAllValues().stream().map(r -> r.path("store").asString())).containsExactlyInAnyOrder("VECTOR", "NEO4J");
        for (JsonNode request : sent.getAllValues()) {
            // 보낸 내용은 저장한 요청 전체와 같다(작업 ID·attempt·digest·승인 본문).
            assertThat(request).isEqualTo(storedRequest(caseId, request.path("store").asString()));
            assertThat(request.path("attempt").asInt()).isEqualTo(1);
        }
    }

    @Test
    void 두_저장소가_같은_승인_버전으로_성공하면_그때서야_공개된다() throws Exception {
        when(client.applyIndex(any())).thenAnswer(call -> snapshot(call.getArgument(0), "SUCCEEDED"));
        UUID caseId = approved();

        awaitStates(caseId, "SUCCEEDED", "SUCCEEDED");
        JsonNode body = result(caseDetail(staff, caseId));
        assertThat(body.path("publication").path("state").asString()).isEqualTo("SEARCHABLE");
        assertThat(body.path("publication").path("canSearch").asBoolean()).isTrue();
        assertThat(body.path("indexes").findValuesAsString("state")).containsExactly("SUCCEEDED", "SUCCEEDED");
        JsonNode item = result(cases(staff)).path("items").get(0);
        assertThat(item.path("publishedVersions")).hasSize(1);
        assertThat(item.path("publishedVersions").get(0).asInt()).isEqualTo(1);
    }

    @Test
    void 한쪽만_성공하면_공개하지_않고_실패한_쪽은_FAILED와_오류를_보여_준다() throws Exception {
        when(client.applyIndex(any())).thenAnswer(call -> snapshot(call.getArgument(0),
                "VECTOR".equals(call.getArgument(0, JsonNode.class).path("store").asString()) ? "SUCCEEDED" : "FAILED"));
        UUID caseId = approved();

        awaitStates(caseId, "SUCCEEDED", "FAILED");
        JsonNode body = result(caseDetail(staff, caseId));
        assertThat(body.path("publication").path("state").asString()).isEqualTo("PRIVATE");
        assertThat(body.path("publication").path("canSearch").asBoolean()).isFalse();
        for (JsonNode index : body.path("indexes")) {
            boolean failed = "NEO4J".equals(index.path("store").asString());
            assertThat(index.path("state").asString()).isEqualTo(failed ? "FAILED" : "SUCCEEDED");
            assertThat(index.path("error").isNull()).isEqualTo(!failed);
        }
        assertThat(result(cases(staff)).path("items").get(0).path("publishedVersions")).isEmpty();
    }

    @Test
    void 접수_응답이_진행_중이면_RUNNING으로_보인다() throws Exception {
        when(client.applyIndex(any())).thenAnswer(call -> snapshot(call.getArgument(0), "RUNNING"));
        UUID caseId = approved();

        awaitStates(caseId, "RUNNING", "RUNNING");
        JsonNode body = result(caseDetail(staff, caseId));
        assertThat(body.path("indexes").findValuesAsString("state")).containsExactly("RUNNING", "RUNNING");
        assertThat(body.path("publication").path("state").asString()).isEqualTo("PRIVATE");
    }

    @Test
    void AI_호출이_실패해도_승인은_유지되고_반영은_대기로_남는다() throws Exception {
        when(client.applyIndex(any())).thenThrow(new AgentRunClientException(AgentRunClientException.SERVICE_UNAVAILABLE,
                true, 0, null, "연결 실패", null));
        UUID caseId = approved();

        JsonNode body = result(caseDetail(staff, caseId));
        assertThat(body.path("version").path("state").asString()).isEqualTo("APPROVED");
        assertThat(body.path("indexes").findValuesAsString("state")).containsExactly("PENDING", "PENDING");
        assertThat(stored(caseId)).containsEntry("vector_status", "PENDING").containsEntry("graph_status", "PENDING");
    }

    @Test
    void 응답이_저장한_요청과_다르면_상태를_바꾸지_않는다() throws Exception {
        when(client.applyIndex(any())).thenAnswer(call -> {
            ObjectNode other = snapshot(call.getArgument(0), "SUCCEEDED");
            other.put("contentDigest", "f".repeat(64));
            return other;
        });
        UUID caseId = approved();

        assertThat(stored(caseId)).containsEntry("vector_status", "PENDING").containsEntry("graph_status", "PENDING");
        assertThat(result(caseDetail(staff, caseId)).path("publication").path("state").asString()).isEqualTo("PRIVATE");
    }

    @Test
    void 승인이_거절되면_AI에_아무것도_보내지_않는다() throws Exception {
        UUID caseId = readyDraft();
        mvc.perform(approvals(staff, caseId, approve(9))).andExpect(status().isConflict());
        awaitDeliveries();
        verify(client, never()).applyIndex(any());
    }

    @Test
    void 같은_승인_요청을_다시_보내도_다시_전달하지_않는다() throws Exception {
        UUID caseId = readyDraft();
        ObjectNode command = approve(1);
        result(approvals(staff, caseId, command));
        verify(client, timeout(10_000).times(2)).applyIndex(any());

        result(approvals(staff, caseId, command));
        awaitDeliveries();
        verify(client, times(2)).applyIndex(any());
    }

    // ---------- index-checks ----------

    @Test
    void 확인은_저장한_같은_작업과_attempt를_조회하고_결과를_반영한다() throws Exception {
        UUID caseId = approved();
        stubRemote("SUCCEEDED");

        result(checks(staff, caseId, check(1, "VECTOR")));

        awaitStates(caseId, "SUCCEEDED", "PENDING");
        JsonNode stored = storedRequest(caseId, "VECTOR");
        verify(client).indexSnapshot(eq(stored.path("jobId").asString()), eq(1));
        verify(client, times(2)).applyIndex(any()); // 존재하는 작업을 새로 접수하지 않는다
        verify(client, never()).indexSnapshot(eq(storedRequest(caseId, "NEO4J").path("jobId").asString()), anyInt());
        assertThat(storedRequest(caseId, "VECTOR")).isEqualTo(stored);
    }

    @Test
    void 원격이_진행_중이면_RUNNING이고_FAILED면_FAILED를_반영한다() throws Exception {
        UUID caseId = approved();
        stubRemote("RUNNING");
        result(checks(staff, caseId, check(1, "VECTOR")));
        awaitStates(caseId, "RUNNING", "PENDING");

        stubRemote("FAILED");
        result(checks(staff, caseId, check(1, "NEO4J")));
        awaitStates(caseId, "RUNNING", "FAILED");
    }

    @Test
    void 유효한_원격_NOT_FOUND이면_보존한_같은_요청_전체를_한_번_다시_접수한다() throws Exception {
        UUID caseId = approved();
        JsonNode stored = storedRequest(caseId, "VECTOR");
        when(client.indexSnapshot(any(), anyInt())).thenThrow(new AgentRunClientException("NOT_FOUND", false, 404, null,
                "없음", null));
        when(client.applyIndex(any())).thenAnswer(call -> snapshot(call.getArgument(0), "SUCCEEDED"));

        result(checks(staff, caseId, check(1, "VECTOR")));

        awaitStates(caseId, "SUCCEEDED", "PENDING");
        ArgumentCaptor<JsonNode> sent = ArgumentCaptor.forClass(JsonNode.class);
        verify(client, times(3)).applyIndex(sent.capture());
        // 새 작업·attempt·digest를 만들지 않고 저장한 요청 그대로다.
        assertThat(sent.getAllValues().getLast()).isEqualTo(stored);
        assertThat(storedRequest(caseId, "VECTOR")).isEqualTo(stored);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SERVICE_UNAVAILABLE", "UPSTREAM_PROTOCOL_ERROR", "AUTH_CONTEXT_INVALID"})
    void 시간초과_5xx_인증_형식_오류이면_재전달하지_않고_미확인으로_둔다(String code) throws Exception {
        UUID caseId = approved();
        when(client.indexSnapshot(any(), anyInt())).thenThrow(new AgentRunClientException(code, false, 0, null, "오류", null));

        result(checks(staff, caseId, check(1, "VECTOR")));

        awaitStates(caseId, "UNKNOWN", "PENDING");
        verify(client, times(2)).applyIndex(any());
        assertThat(result(caseDetail(staff, caseId)).path("publication").path("state").asString()).isEqualTo("PRIVATE");
    }

    @Test
    void 재접수가_실패하면_성공으로_바꾸지_않고_미확인으로_둔다() throws Exception {
        UUID caseId = approved();
        when(client.indexSnapshot(any(), anyInt())).thenThrow(new AgentRunClientException("NOT_FOUND", false, 404, null,
                "없음", null));
        when(client.applyIndex(any())).thenThrow(new AgentRunClientException(AgentRunClientException.SERVICE_UNAVAILABLE,
                true, 0, null, "연결 실패", null));

        result(checks(staff, caseId, check(1, "VECTOR")));

        awaitStates(caseId, "UNKNOWN", "PENDING");
    }

    @Test
    void 조회_응답이_저장한_요청과_다르면_성공으로_받지_않고_미확인으로_둔다() throws Exception {
        UUID caseId = approved();
        when(client.indexSnapshot(any(), anyInt())).thenAnswer(call -> {
            ObjectNode other = snapshot(requestByJob(call.getArgument(0, String.class)), "SUCCEEDED");
            other.put("attempt", 7);
            return other;
        });

        result(checks(staff, caseId, check(1, "VECTOR")));

        awaitStates(caseId, "UNKNOWN", "PENDING");
    }

    @Test
    void 이미_미확인인_저장소는_다시_조회만_하고_원격_UNKNOWN을_그대로_반영한다() throws Exception {
        UUID caseId = approved();
        jdbc.update("UPDATE resolved_case SET vector_status='UNKNOWN' WHERE id=?", caseId);
        stubRemote("UNKNOWN");

        result(checks(staff, caseId, check(1, "VECTOR")));

        verify(client, timeout(10_000)).indexSnapshot(any(), eq(1));
        awaitStates(caseId, "UNKNOWN", "PENDING");
        verify(client, times(2)).applyIndex(any());
    }

    @Test
    void 새_확인_클릭은_새_requestId를_쓰지만_같은_원격_작업을_확인한다() throws Exception {
        UUID caseId = approved();
        String jobId = storedRequest(caseId, "VECTOR").path("jobId").asString();
        stubRemote("RUNNING");
        result(checks(staff, caseId, check(1, "VECTOR")));
        awaitStates(caseId, "RUNNING", "PENDING");
        result(checks(staff, caseId, check(1, "VECTOR")));

        verify(client, timeout(10_000).times(2)).indexSnapshot(eq(jobId), eq(1));
        assertThat(storedRequest(caseId, "VECTOR").path("jobId").asString()).isEqualTo(jobId);
        assertThat(storedRequest(caseId, "VECTOR").path("attempt").asInt()).isEqualTo(1);
    }

    @Test
    void 같은_확인_요청을_다시_보내면_처음_결과를_돌려주고_다시_조회하지_않는다() throws Exception {
        UUID caseId = approved();
        stubRemote("RUNNING");
        ObjectNode command = check(1, "VECTOR");
        JsonNode first = result(checks(staff, caseId, command));
        verify(client, timeout(10_000)).indexSnapshot(any(), eq(1));

        JsonNode second = result(checks(staff, caseId, command));
        awaitDeliveries();
        assertThat(second.path("operationId").asString()).isEqualTo(first.path("operationId").asString());
        assertThat(second.path("resource").path("type").asString()).isEqualTo("CASE");
        assertThat(second.path("resource").path("id").asString()).isEqualTo(caseId.toString());
        verify(client, times(1)).indexSnapshot(any(), eq(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCEEDED", "FAILED"})
    void 이미_끝난_저장소는_확인_대상이_아니라_409_INVALID_STATE다(String state) throws Exception {
        UUID caseId = approved();
        jdbc.update("UPDATE resolved_case SET vector_status=? WHERE id=?", state, caseId);
        mvc.perform(checks(staff, caseId, check(1, "VECTOR"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        verify(client, never()).indexSnapshot(any(), anyInt());
    }

    @Test
    void 승인_전_사례의_확인은_409_INVALID_STATE다() throws Exception {
        UUID caseId = readyDraft();
        mvc.perform(checks(staff, caseId, check(1, "VECTOR"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    @Test
    void 확인도_오래된_expectedCaseVersion은_409_CASE_VERSION_CONFLICT다() throws Exception {
        UUID caseId = approved();
        mvc.perform(checks(staff, caseId, check(4, "VECTOR"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CASE_VERSION_CONFLICT"));
        verify(client, never()).indexSnapshot(any(), anyInt());
    }

    // ---------- index-retries ----------

    @Test
    void 실패한_저장소만_같은_작업의_다음_attempt로_저장하고_전달하며_성공한_저장소는_유지한다() throws Exception {
        when(client.applyIndex(any())).thenAnswer(call -> snapshot(call.getArgument(0),
                "VECTOR".equals(call.getArgument(0, JsonNode.class).path("store").asString()) ? "SUCCEEDED" : "FAILED"));
        UUID caseId = approved();
        awaitStates(caseId, "SUCCEEDED", "FAILED");
        JsonNode before = storedRequest(caseId, "NEO4J");
        JsonNode vectorBefore = storedRequest(caseId, "VECTOR");
        when(client.applyIndex(any())).thenAnswer(call -> snapshot(call.getArgument(0), "RUNNING"));

        JsonNode receipt = result(retries(staff, caseId, retry(1, "NEO4J")));
        assertThat(receipt.path("resource").path("type").asString()).isEqualTo("CASE");

        JsonNode after = storedRequest(caseId, "NEO4J");
        assertThat(after.path("attempt").asInt()).isEqualTo(2);
        assertThat(after.path("jobId")).isEqualTo(before.path("jobId"));
        assertThat(after.path("contentDigest")).isEqualTo(before.path("contentDigest"));
        assertThat(after.path("approvedCase")).isEqualTo(before.path("approvedCase"));
        assertThat(storedRequest(caseId, "VECTOR")).isEqualTo(vectorBefore);
        ArgumentCaptor<JsonNode> sent = ArgumentCaptor.forClass(JsonNode.class);
        verify(client, timeout(10_000).times(3)).applyIndex(sent.capture());
        assertThat(sent.getAllValues().getLast()).isEqualTo(after);
        awaitStates(caseId, "SUCCEEDED", "RUNNING");
        JsonNode indexes = result(caseDetail(staff, caseId)).path("indexes");
        for (JsonNode index : indexes) {
            boolean retried = "NEO4J".equals(index.path("store").asString());
            assertThat(index.path("attempt").asInt()).isEqualTo(retried ? 2 : 1);
            assertThat(index.path("error").isNull()).isTrue();
        }
    }

    @Test
    void 재시도_전달이_실패해도_새_attempt는_대기로_저장돼_있다() throws Exception {
        UUID caseId = approved();
        jdbc.update("UPDATE resolved_case SET graph_status='FAILED' WHERE id=?", caseId);
        when(client.applyIndex(any())).thenThrow(new AgentRunClientException(AgentRunClientException.SERVICE_UNAVAILABLE,
                true, 0, null, "연결 실패", null));

        result(retries(staff, caseId, retry(1, "NEO4J")));

        verify(client, timeout(10_000).times(3)).applyIndex(any());
        assertThat(stored(caseId)).containsEntry("graph_status", "PENDING");
        assertThat(storedRequest(caseId, "NEO4J").path("attempt").asInt()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "RUNNING", "SUCCEEDED", "UNKNOWN"})
    void 실패가_아닌_저장소는_409_INDEX_NOT_RETRYABLE이고_바뀌지_않는다(String state) throws Exception {
        UUID caseId = approved();
        jdbc.update("UPDATE resolved_case SET vector_status=? WHERE id=?", state, caseId);
        Map<String, Object> before = stored(caseId);
        JsonNode request = storedRequest(caseId, "VECTOR");

        mvc.perform(retries(staff, caseId, retry(1, "VECTOR"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INDEX_NOT_RETRYABLE"));
        assertThat(stored(caseId)).isEqualTo(before);
        assertThat(storedRequest(caseId, "VECTOR")).isEqualTo(request);
        verify(client, times(2)).applyIndex(any());
    }

    @Test
    void 승인_전_사례의_재시도는_409_INVALID_STATE이고_오래된_버전은_CASE_VERSION_CONFLICT다() throws Exception {
        UUID draft = readyDraft();
        mvc.perform(retries(staff, draft, retry(1, "VECTOR"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        mvc.perform(retries(staff, draft, retry(5, "VECTOR"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CASE_VERSION_CONFLICT"));
    }

    @Test
    void 같은_재시도_요청을_다시_보내면_attempt를_다시_올리지_않는다() throws Exception {
        UUID caseId = approved();
        jdbc.update("UPDATE resolved_case SET vector_status='FAILED' WHERE id=?", caseId);
        ObjectNode command = retry(1, "VECTOR");
        JsonNode first = result(retries(staff, caseId, command));
        JsonNode second = result(retries(staff, caseId, command));
        assertThat(second.path("operationId").asString()).isEqualTo(first.path("operationId").asString());
        assertThat(storedRequest(caseId, "VECTOR").path("attempt").asInt()).isEqualTo(2);
    }

    @Test
    void 같은_저장소를_동시에_재시도하면_하나만_다음_attempt가_되고_하나는_409다() throws Exception {
        UUID caseId = approved();
        jdbc.update("UPDATE resolved_case SET vector_status='FAILED' WHERE id=?", caseId);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit(statusOf(start, retries(staff, caseId, retry(1, "VECTOR"))));
            Future<Integer> b = pool.submit(statusOf(start, retries(staff, caseId, retry(1, "VECTOR"))));
            start.countDown();
            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(storedRequest(caseId, "VECTOR").path("attempt").asInt()).isEqualTo(2);
    }

    // ---------- 권한·입력 ----------

    @Test
    void 담당자가_아니거나_없는_사례는_404이고_AI를_부르지_않는다() throws Exception {
        UUID caseId = approved();
        jdbc.update("UPDATE resolved_case SET vector_status='FAILED' WHERE id=?", caseId);
        for (UUID user : List.of(other, requester, admin)) {
            mvc.perform(checks(user, caseId, check(1, "VECTOR"))).andExpect(status().isNotFound());
            mvc.perform(retries(user, caseId, retry(1, "VECTOR"))).andExpect(status().isNotFound());
        }
        mvc.perform(checks(other, UUID.randomUUID(), check(1, "VECTOR"))).andExpect(status().isNotFound());
        assertThat(stored(caseId)).containsEntry("vector_status", "FAILED");
        verify(client, never()).indexSnapshot(any(), anyInt());
        verify(client, times(2)).applyIndex(any());
    }

    @Test
    void 이미_처리한_요청도_권한을_잃으면_같은_키_재확인이_404다() throws Exception {
        UUID caseId = approved();
        stubRemote("RUNNING");
        ObjectNode command = check(1, "VECTOR");
        result(checks(staff, caseId, command));
        jdbc.update("UPDATE it_system SET primary_owner_member_id=? WHERE id=?", other, system);
        mvc.perform(checks(staff, caseId, command)).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"requestId", "expectedCaseVersion", "store"})
    void 필수_필드가_없으면_400이다(String field) throws Exception {
        UUID caseId = approved();
        for (String action : List.of("index-checks", "index-retries")) {
            ObjectNode command = check(1, "VECTOR");
            command.remove(field);
            mvc.perform(as(staff, post("/api/v2/cases/" + caseId + "/" + action).contentType("application/json")
                    .header("Idempotency-Key", UUID.randomUUID().toString()).content(command.toString())))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"vector", "REDIS", "", "BOTH"})
    void 알_수_없는_저장소는_400이다(String store) throws Exception {
        UUID caseId = approved();
        mvc.perform(checks(staff, caseId, check(1, store))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(retries(staff, caseId, retry(1, store))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void 기대_버전이_양수가_아니면_400이다(int version) throws Exception {
        UUID caseId = approved();
        mvc.perform(checks(staff, caseId, check(version, "VECTOR"))).andExpect(status().isBadRequest());
        mvc.perform(retries(staff, caseId, retry(version, "VECTOR"))).andExpect(status().isBadRequest());
    }

    // ---------- 준비 ----------

    /** 해결 확인된 초안을 승인하고 두 저장소의 최초 전달이 끝날 때까지 기다린다. */
    private UUID approved() throws Exception {
        UUID caseId = readyDraft();
        result(approvals(staff, caseId, approve(1)));
        verify(client, timeout(10_000).times(2)).applyIndex(any());
        awaitDeliveries();
        return caseId;
    }

    private void awaitStates(UUID caseId, String vector, String graph) {
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Map<String, Object> row = stored(caseId);
            assertThat(row.get("vector_status")).isEqualTo(vector);
            assertThat(row.get("graph_status")).isEqualTo(graph);
        });
    }

    /** 원격이 저장한 요청에 대해 같은 작업 ID·attempt·digest의 지정한 상태를 돌려주게 한다. */
    private void stubRemote(String state) {
        when(client.indexSnapshot(any(), anyInt())).thenAnswer(call ->
                snapshot(requestByJob(call.getArgument(0, String.class)), state));
    }

    private ObjectNode snapshot(JsonNode request, String state) {
        ObjectNode snapshot = mapper.createObjectNode();
        snapshot.put("contractVersion", "hops-system-1");
        for (String field : List.of("jobId", "caseId", "store", "contentDigest")) {
            snapshot.put(field, request.path(field).asString());
        }
        snapshot.put("caseVersion", request.path("caseVersion").asInt());
        snapshot.put("attempt", request.path("attempt").asInt());
        snapshot.put("state", state);
        if ("FAILED".equals(state)) {
            snapshot.putObject("error").put("code", "INDEX_UNAVAILABLE").put("message", "반영 실패").put("retryable", false);
        } else {
            snapshot.putNull("error");
        }
        snapshot.put("updatedAt", java.time.Instant.now().toString());
        return snapshot;
    }

    private ObjectNode approve(int expectedCaseVersion) {
        return mapper.createObjectNode().put("requestId", UUID.randomUUID().toString())
                .put("expectedCaseVersion", expectedCaseVersion);
    }

    private ObjectNode check(int expectedCaseVersion, String store) {
        return mapper.createObjectNode().put("requestId", UUID.randomUUID().toString())
                .put("expectedCaseVersion", expectedCaseVersion).put("store", store);
    }

    private ObjectNode retry(int expectedCaseVersion, String store) {
        return check(expectedCaseVersion, store);
    }

    private MockHttpServletRequestBuilder approvals(UUID user, UUID caseId, JsonNode body) {
        return command(user, "/cases/" + caseId + "/approvals", body);
    }

    private MockHttpServletRequestBuilder checks(UUID user, UUID caseId, JsonNode body) {
        return command(user, "/cases/" + caseId + "/index-checks", body);
    }

    private MockHttpServletRequestBuilder retries(UUID user, UUID caseId, JsonNode body) {
        return command(user, "/cases/" + caseId + "/index-retries", body);
    }

    private JsonNode storedRequest(UUID caseId, String store) throws Exception {
        String metadata = jdbc.queryForObject(
                "SELECT source_metadata::text FROM knowledge_document_version WHERE source_metadata ->> 'caseId' = ?",
                String.class, caseId.toString());
        return mapper.readTree(metadata).path("indexRequests").path(store);
    }

    private JsonNode requestByJob(String jobId) {
        for (String metadata : jdbc.queryForList(
                "SELECT source_metadata::text FROM knowledge_document_version WHERE source_metadata::text LIKE ?",
                String.class, "%" + jobId + "%")) {
            JsonNode requests = mapper.readTree(metadata).path("indexRequests");
            for (String store : List.of("VECTOR", "NEO4J")) {
                if (jobId.equals(requests.path(store).path("jobId").asString())) return requests.path(store);
            }
        }
        throw new IllegalStateException("저장된 요청 없음: " + jobId);
    }

    private Callable<Integer> statusOf(CountDownLatch start, MockHttpServletRequestBuilder request) {
        return () -> {
            start.await();
            return mvc.perform(request).andReturn().getResponse().getStatus();
        };
    }

    private Map<String, Object> stored(UUID caseId) {
        return jdbc.queryForMap("SELECT case_status,version_no,vector_status,graph_status FROM resolved_case WHERE id=?",
                caseId);
    }

    private MockHttpServletRequestBuilder cases(UUID user) {
        return as(user, get("/api/v2/cases"));
    }

    private MockHttpServletRequestBuilder caseDetail(UUID user, UUID caseId) {
        return as(user, get("/api/v2/cases/" + caseId));
    }
}
