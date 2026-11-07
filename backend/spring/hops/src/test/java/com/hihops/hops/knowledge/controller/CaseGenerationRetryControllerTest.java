package com.hihops.hops.knowledge.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.hihops.hops.agent.client.AgentRunClientException;

import tools.jackson.databind.JsonNode;

/**
 * 실패한 사례 생성 재시도 (WIRE_CONTRACT 12.2 POST /case-generations/{generationId}/retries, 현재 담당자·FAILED).
 *
 * <p>AI 서버는 같은 runId를 다시 받으면 이전 결과를 재생하므로 재시도는 같은 처리 기록에 새 실행(새 generationId)을 접수한다.
 * 이전 실행은 FAILED로 남고, 티켓의 현재 사례 생성은 가장 나중 실행이다.
 */
class CaseGenerationRetryControllerTest extends CaseDraftFixture {

    // ---------- 성공 ----------

    @Test
    void 실패한_생성을_재시도하면_새_실행을_접수하고_성공하면_사례가_생긴다() throws Exception {
        UUID failed = failedGeneration();
        assertThat(detail().path("caseGeneration").path("state").asString()).isEqualTo("FAILED");
        aiSucceeds();

        JsonNode receipt = result(retry(staff, failed, UUID.randomUUID()));
        assertThat(receipt.path("state").asString()).isEqualTo("SUCCEEDED");
        assertThat(receipt.path("resource").path("type").asString()).isEqualTo("CASE_GENERATION");
        UUID next = UUID.fromString(receipt.path("resource").path("id").asString());
        assertThat(next).isNotEqualTo(failed);
        assertThat(receipt.path("error").isNull()).isTrue();

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(detail().path("caseGeneration").path("state").asString()).isEqualTo("SUCCEEDED"));
        JsonNode current = detail().path("caseGeneration");
        assertThat(current.path("id").asString()).isEqualTo(next.toString());
        assertThat(current.path("caseId").isNull()).isFalse();
        // 이전 실행은 실패 기록으로 남고 같은 처리 기록을 가리킨다
        JsonNode old = result(as(staff, get("/api/v2/case-generations/" + failed)));
        assertThat(old.path("state").asString()).isEqualTo("FAILED");
        assertThat(old.path("workRecordId")).isEqualTo(current.path("workRecordId"));
        assertThat(runCount()).isEqualTo(2);
    }

    @Test
    void 새_실행은_같은_원_기록으로_QUEUED_입력을_고정해_접수한다() throws Exception {
        UUID failed = failedGeneration();
        // AI가 받기 전 상태를 보려고 전달을 막는다
        doThrow(new AgentRunClientException(AgentRunClientException.SERVICE_UNAVAILABLE, true, 503, null, "다시 실패", null))
                .when(client).submit(any());
        UUID next = generationId(result(retry(staff, failed, UUID.randomUUID())));
        awaitDeliveries();

        var oldInput = json(jdbc.queryForObject("SELECT input_snapshot::text FROM agent_run WHERE id=?", String.class, failed));
        var newInput = json(jdbc.queryForObject("SELECT input_snapshot::text FROM agent_run WHERE id=?", String.class, next));
        assertThat(newInput.path("runId").asString()).isEqualTo(next.toString());
        assertThat(newInput.path("kind").asString()).isEqualTo("CASE_DRAFT");
        assertThat(newInput.path("workRecord").path("id")).isEqualTo(oldInput.path("workRecord").path("id"));
        assertThat(newInput.path("workRecord").path("resolutionRequestId")).isEqualTo(oldInput.path("workRecord").path("resolutionRequestId"));
        assertThat(newInput.path("accessContext").path("actorId").asString()).isEqualTo(staff.toString());
        assertThat(jdbc.queryForObject("SELECT ticket_id FROM agent_run WHERE id=?", UUID.class, next)).isEqualTo(ticket);
        assertThat(jdbc.queryForObject("SELECT trigger_work_log_id FROM agent_run WHERE id=?", UUID.class, next))
                .isEqualTo(jdbc.queryForObject("SELECT trigger_work_log_id FROM agent_run WHERE id=?", UUID.class, failed));
    }

    @Test
    void 다시_실패하면_또_재시도할_수_있다() throws Exception {
        UUID first = failedGeneration();
        doThrow(unavailable()).when(client).submit(any());
        UUID second = generationId(result(retry(staff, first, UUID.randomUUID())));
        awaitState("FAILED", second);
        aiSucceeds();
        UUID third = generationId(result(retry(staff, second, UUID.randomUUID())));
        assertThat(List.of(first, second, third)).doesNotHaveDuplicates();
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(detail().path("caseGeneration").path("state").asString()).isEqualTo("SUCCEEDED"));
        assertThat(runCount()).isEqualTo(3);
    }

    // ---------- 멱등 ----------

    @Test
    void 같은_요청_키는_처음_결과를_돌려주고_새_실행을_만들지_않는다() throws Exception {
        UUID failed = failedGeneration();
        aiSucceeds();
        UUID key = UUID.randomUUID();
        JsonNode first = result(retry(staff, failed, key));
        JsonNode second = result(retry(staff, failed, key));
        assertThat(second).isEqualTo(first);
        awaitDeliveries();
        assertThat(runCount()).isEqualTo(2);
    }

    @Test
    void 같은_요청_키를_다른_대상에_쓰면_충돌이고_실행을_더_만들지_않는다() throws Exception {
        UUID failed = failedGeneration();
        aiSucceeds();
        UUID key = UUID.randomUUID();
        UUID next = generationId(result(retry(staff, failed, key)));
        awaitDeliveries();
        mvc.perform(retry(staff, next, key)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(runCount()).isEqualTo(2);
    }

    @Test
    void 다른_키로_동시에_재시도해도_새_실행은_하나다() throws Exception {
        UUID failed = failedGeneration();
        aiSucceeds();
        var one = retry(staff, failed, UUID.randomUUID());
        var two = retry(staff, failed, UUID.randomUUID());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = pool.invokeAll(List.<Callable<Integer>>of(
                    () -> mvc.perform(one).andReturn().getResponse().getStatus(),
                    () -> mvc.perform(two).andReturn().getResponse().getStatus()));
            assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(200, 409);
        }
        awaitDeliveries();
        assertThat(runCount()).isEqualTo(2);
    }

    // ---------- 상태 ----------

    @Test
    void 실패가_아닌_생성은_재시도할_수_없다() throws Exception {
        UUID failed = failedGeneration();
        // 대기·실행 중
        jdbc.update("UPDATE agent_run SET run_status='QUEUED',started_at=NULL,completed_at=NULL,error_code=NULL WHERE id=?", failed);
        rejected(failed, "INVALID_STATE");
        jdbc.update("UPDATE agent_run SET run_status='RUNNING',started_at=now() WHERE id=?", failed);
        rejected(failed, "INVALID_STATE");
        assertThat(runCount()).isEqualTo(1);
    }

    @Test
    void 성공한_생성과_재사용_사례_없음은_재시도할_수_없다() throws Exception {
        draft();
        UUID succeeded = UUID.fromString(detail().path("caseGeneration").path("id").asString());
        rejected(succeeded, "INVALID_STATE");

        jdbc.update("DELETE FROM resolved_case WHERE source_work_log_id IN (SELECT id FROM ticket_work_log WHERE ticket_id=?)", ticket);
        jdbc.update("UPDATE agent_run SET validation_result='{\"applied\":true,\"resources\":[],\"caseGenerationState\":\"NO_REUSABLE_CASE\"}'::jsonb WHERE id=?", succeeded);
        assertThat(detail().path("caseGeneration").path("state").asString()).isEqualTo("NO_REUSABLE_CASE");
        rejected(succeeded, "INVALID_STATE");
        assertThat(runCount()).isEqualTo(1);
    }

    @Test
    void 이미_다시_접수된_이전_실행은_재시도할_수_없다() throws Exception {
        UUID first = failedGeneration();
        doThrow(unavailable()).when(client).submit(any());
        UUID second = generationId(result(retry(staff, first, UUID.randomUUID())));
        awaitState("FAILED", second);
        // 이전 실행은 FAILED이지만 현재 사례 생성이 아니다
        rejected(first, "INVALID_STATE");
        assertThat(runCount()).isEqualTo(2);
        UUID third = generationId(result(retry(staff, second, UUID.randomUUID())));
        assertThat(third).isNotEqualTo(second);
    }

    @Test
    void 사례가_이미_있으면_재시도하지_않는다() throws Exception {
        UUID failed = failedGeneration();
        // 늦게 도착한 결과로 사례가 저장된 경우를 흉내 낸다
        jdbc.update("""
                INSERT INTO resolved_case(case_number,source_work_log_id,it_system_id,case_type,title,problem_statement,
                action_detail,verification_result,access_level,origin_type,curator_run_id)
                SELECT 'CASE-' || substr(?,1,12),w.id,?,'RESOLUTION','가상','가상','가상','가상','STAFF','SYNTHETIC',?
                FROM ticket_work_log w WHERE w.ticket_id=?""", UUID.randomUUID().toString(), system, failed, ticket);
        rejected(failed, "INVALID_STATE");
        assertThat(runCount()).isEqualTo(1);
    }

    // ---------- 권한·입력 ----------

    @Test
    void 현재_담당자만_재시도할_수_있고_다른_사람은_거절된다() throws Exception {
        UUID failed = failedGeneration();
        mvc.perform(retry(other, failed, UUID.randomUUID())).andExpect(status().isForbidden());
        mvc.perform(retry(admin, failed, UUID.randomUUID())).andExpect(status().isForbidden());
        mvc.perform(retry(requester, failed, UUID.randomUUID())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v2/case-generations/" + failed + "/retries").contentType("application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString()).content("{}")).andExpect(status().isUnauthorized());
        assertThat(runCount()).isEqualTo(1);
    }

    @Test
    void 담당자가_바뀐_뒤에는_이전_담당자가_재시도할_수_없다() throws Exception {
        UUID failed = failedGeneration();
        jdbc.update("UPDATE it_system SET primary_owner_member_id=? WHERE id=?", other, system);
        jdbc.update("UPDATE ticket SET assignee_member_id=? WHERE id=?", other, ticket);
        mvc.perform(retry(staff, failed, UUID.randomUUID())).andExpect(status().isForbidden());
        assertThat(runCount()).isEqualTo(1);
    }

    @Test
    void 없는_생성이나_사례_생성이_아닌_실행은_404다() throws Exception {
        failedGeneration();
        mvc.perform(retry(staff, UUID.randomUUID(), UUID.randomUUID())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(retry(staff, analysis, UUID.randomUUID())).andExpect(status().isNotFound());
        assertThat(runCount()).isEqualTo(1);
    }

    @Test
    void 요청_키_헤더가_없거나_본문의_requestId와_다르면_400이다() throws Exception {
        UUID failed = failedGeneration();
        String path = "/api/v2/case-generations/" + failed + "/retries";
        String body = "{\"requestId\":\"" + UUID.randomUUID() + "\"}";
        mvc.perform(as(staff, post(path).contentType("application/json").content(body))).andExpect(status().isBadRequest());
        mvc.perform(as(staff, post(path).contentType("application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString()).content(body))).andExpect(status().isBadRequest());
        mvc.perform(as(staff, post(path).contentType("application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString()).content("{}"))).andExpect(status().isBadRequest());
        assertThat(runCount()).isEqualTo(1);
    }

    @Test
    void 거절된_재시도는_실행을_만들지_않고_이후_정상_재시도를_막지_않는다() throws Exception {
        UUID failed = failedGeneration();
        mvc.perform(retry(other, failed, UUID.randomUUID())).andExpect(status().isForbidden());
        aiSucceeds();
        result(retry(staff, failed, UUID.randomUUID()));
        awaitDeliveries();
        assertThat(runCount()).isEqualTo(2);
    }

    // ---------- 준비 ----------

    /** AI 접수가 거절돼 FAILED가 된 사례 생성의 id. */
    private UUID failedGeneration() throws Exception {
        doThrow(unavailable()).when(client).submit(any());
        result(command(staff, "/tickets/" + ticket + "/work-records", work("VERIFIED_RESOLUTION")));
        UUID id = jdbc.queryForObject("SELECT id FROM agent_run WHERE ticket_id=? AND task='CURATION'", UUID.class, ticket);
        awaitState("FAILED", id);
        return id;
    }

    private void aiSucceeds() {
        doNothing().when(client).submit(any());
        doAnswer(invocation -> reusableSnapshot(invocation.getArgument(0, String.class))).when(client).snapshot(any());
    }

    private static AgentRunClientException unavailable() {
        return new AgentRunClientException(AgentRunClientException.SERVICE_UNAVAILABLE, true, 503, null, "AI 접수 실패", null);
    }

    private void awaitState(String state, UUID id) {
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(result(as(staff, get("/api/v2/case-generations/" + id))).path("state").asString()).isEqualTo(state));
        awaitDeliveries();
    }

    private void rejected(UUID id, String code) throws Exception {
        mvc.perform(retry(staff, id, UUID.randomUUID())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(code));
    }

    private UUID generationId(JsonNode receipt) {
        return UUID.fromString(receipt.path("resource").path("id").asString());
    }

    private int runCount() {
        return jdbc.queryForObject("SELECT count(*) FROM agent_run WHERE ticket_id=? AND task='CURATION'", Integer.class, ticket);
    }

    private MockHttpServletRequestBuilder retry(UUID user, UUID generation, UUID requestId) {
        return command(user, "/case-generations/" + generation + "/retries", mapper.createObjectNode().put("requestId", requestId.toString()));
    }
}
