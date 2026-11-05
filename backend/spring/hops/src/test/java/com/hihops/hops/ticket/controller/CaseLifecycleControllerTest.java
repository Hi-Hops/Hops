package com.hihops.hops.ticket.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.hihops.hops.knowledge.service.CaseIndexClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 실제 PostgreSQL 거래와 공개 REST 계약. AI 색인 응답은 명시적인 대역이다. */
class CaseLifecycleControllerTest extends TicketCommandFixture {
    @MockitoBean CaseIndexClient indexClient;
    @org.springframework.beans.factory.annotation.Autowired
    com.hihops.hops.knowledge.service.KnowledgeAuthorizationService authorizations;
    private final UUID caseId = UUID.randomUUID();
    private final AtomicReference<String> vector = new AtomicReference<>("SUCCEEDED");
    private final AtomicReference<String> graph = new AtomicReference<>("SUCCEEDED");
    private UUID record;

    @BeforeEach void 사례_준비() throws Exception {
        when(indexClient.submit(any(), any(), any())).thenAnswer(call -> indexClient.send(call.getArgument(0), false));
        when(indexClient.send(any(), anyBoolean())).thenAnswer(call -> {
            var request = (JsonNode) call.getArgument(0);
            String state = "VECTOR".equals(request.path("store").asString()) ? vector.get() : graph.get();
            var snapshot = (ObjectNode) request.deepCopy(); snapshot.remove("approvedCase");
            snapshot.put("state", state).put("updatedAt", Instant.now().toString()).putNull("error");
            if ("FAILED".equals(state)) snapshot.set("error", json("{\"code\":\"SEARCH_UNAVAILABLE\",\"message\":\"대역 실패\",\"retryable\":true}"));
            return new CaseIndexClient.Response(false, snapshot);
        });
        result(command(staff, "/tickets/" + ticket + "/work-records", work("VERIFIED_RESOLUTION")));
        record = jdbc.queryForObject("SELECT id FROM ticket_work_log WHERE ticket_id=?", UUID.class, ticket);
        jdbc.update("""
                INSERT INTO resolved_case(id,case_number,source_work_log_id,it_system_id,case_type,title,
                  problem_statement,action_detail,verification_result,applicability,access_level,origin_type,confirmed_facts)
                VALUES (?,?,?,?,'RESOLUTION','초안','문제','실제 조치','정상 동작 확인','결재 시스템','STAFF','SYNTHETIC',
                  '{"facts":[],"limitations":["원 제한"],"sourceMessageIds":[]}'::jsonb)
                """, caseId, "C-" + caseId.toString().substring(0, 12), record, system);
    }
    @AfterEach void 문서_정리() throws Exception {
        // 비동기 AFTER_COMMIT 전달이 이 테스트의 문서를 사용 중이면 종료를 기다린다.
        await(() -> jdbc.queryForObject("SELECT count(*) FROM resolved_case c WHERE c.id=? AND c.case_status='APPROVED' AND NOT EXISTS "
                + "(SELECT 1 FROM knowledge_document_version v WHERE v.source_metadata->>'caseId'=c.id::text "
                + "AND jsonb_exists(v.source_metadata->'indexResults','VECTOR') AND jsonb_exists(v.source_metadata->'indexResults','NEO4J'))",
                Integer.class, caseId) == 0);
        jdbc.update("DELETE FROM knowledge_document_version WHERE source_metadata->>'caseId'=?", caseId.toString());
        jdbc.update("DELETE FROM knowledge_document WHERE source_data->>'caseId'=?", caseId.toString());
    }
    private ObjectNode basic(int version) { return mapper.createObjectNode().put("requestId", UUID.randomUUID().toString()).put("expectedCaseVersion", version); }
    private JsonNode caseDetail() throws Exception { return result(as(staff, get("/api/v2/cases/" + caseId))); }
    private void resolve(String decision) throws Exception {
        var resolution = detail().path("activeResolutionRequest");
        result(command(requester, "/resolution-requests/" + resolution.path("id").asString() + "/responses",
                mapper.createObjectNode().put("requestId", UUID.randomUUID().toString()).put("expectedRequestRevision", 1).put("decision", decision)));
    }
    private JsonNode approve() throws Exception { return result(command(staff, "/cases/" + caseId + "/approvals", basic(1))); }
    private ObjectNode edit() {
        var command = basic(1).put("title", "수정 제목").put("problemStatement", "확인한 문제").put("action", "검토한 조치")
                .put("verification", "검토한 검증").put("applicability", "적용 조건").putNull("appliesToSystemVersion").put("accessScope", "SYSTEM_STAFF");
        command.putArray("limitations").add("제한 보존"); return command;
    }
    private void await(java.util.function.BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(50);
        assertThat(condition.getAsBoolean()).isTrue();
    }
    @Test void 현재_담당자만_조회하고_타시스템과_관리자를_거부한다() throws Exception {
        assertThat(caseDetail().path("version").path("workRecordId").asString()).isEqualTo(record.toString());
        var page = result(as(staff, get("/api/v2/cases")));
        assertThat(page.path("items").get(0).path("id").asString()).isEqualTo(caseId.toString());
        for (var user : List.of(other, admin, requester)) mvc.perform(as(user, get("/api/v2/cases/" + caseId))).andExpect(status().isForbidden());
        mvc.perform(as(staff, get("/api/v2/cases?systemId=" + otherSystem))).andExpect(status().isForbidden());
        mvc.perform(as(staff, get("/api/v2/cases/" + caseId + "?caseVersion=2"))).andExpect(status().isNotFound());
        mvc.perform(as(staff, get("/api/v2/cases?size=101"))).andExpect(status().isBadRequest());
        verifyNoInteractions(indexClient);
    }
    @Test void 초안_수정은_원기록을_보존하고_버전을_증가시키며_같은키를_재실행하지_않는다() throws Exception {
        var edit = edit(); var first = result(command(staff, "/cases/" + caseId + "/versions", edit));
        assertThat(result(command(staff, "/cases/" + caseId + "/versions", edit))).isEqualTo(first);
        var value = caseDetail().path("version");
        assertThat(value.path("caseVersion").asInt()).isEqualTo(2);
        assertThat(value.path("action").asString()).isEqualTo("검토한 조치");
        assertThat(value.path("limitations").get(0).asString()).isEqualTo("제한 보존");
        assertThat(jdbc.queryForObject("SELECT action_detail FROM ticket_work_log WHERE id=?", String.class, record)).isEqualTo("실제 조치");
        mvc.perform(command(staff, "/cases/" + caseId + "/versions", edit())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CASE_VERSION_CONFLICT"));
        edit.put("title", "같은키 다른본문");
        mvc.perform(command(staff, "/cases/" + caseId + "/versions", edit)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }
    @Test void 해결확인전과_미해결_사례는_승인하지_않는다() throws Exception {
        mvc.perform(command(staff, "/cases/" + caseId + "/approvals", basic(1))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPROVAL_PRECONDITION_FAILED"));
        resolve("UNRESOLVED");
        mvc.perform(command(staff, "/cases/" + caseId + "/approvals", basic(1))).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_document WHERE source_data->>'caseId'=?", Integer.class, caseId.toString())).isZero();
        verifyNoInteractions(indexClient);
    }
    @Test void 자기승인과_동일버전_양쪽성공_후에만_공개한다() throws Exception {
        resolve("RESOLVED"); var receipt = approve();
        assertThat(receipt.path("resource").path("type").asString()).isEqualTo("CASE");
        await(() -> jdbc.queryForObject("SELECT count(*) FROM resolved_case WHERE id=? AND vector_status='SUCCEEDED' AND graph_status='SUCCEEDED'", Integer.class, caseId) == 1);
        var value = caseDetail();
        assertThat(value.path("approval").path("approvedBy").path("id").asString()).isEqualTo(staff.toString());
        assertThat(value.path("publication").path("canSearch").asBoolean()).isTrue();
        assertThat(value.toString()).doesNotContain("indexRequests", "contentDigest", "approvedCase");
        mvc.perform(command(staff, "/cases/" + caseId + "/versions", edit())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }
    @Test void 부분실패는_비공개이며_실패저장소만_다음시도로_복구한다() throws Exception {
        graph.set("FAILED"); resolve("RESOLVED"); approve();
        await(() -> jdbc.queryForObject("SELECT graph_status FROM resolved_case WHERE id=?", String.class, caseId).equals("FAILED"));
        assertThat(caseDetail().path("publication").path("canSearch").asBoolean()).isFalse();
        var before = jdbc.queryForObject("SELECT source_metadata::text FROM knowledge_document_version WHERE source_metadata->>'caseId'=?", String.class, caseId.toString());
        graph.set("SUCCEEDED");
        result(command(staff, "/cases/" + caseId + "/index-retries", basic(1).put("store", "NEO4J")));
        await(() -> jdbc.queryForObject("SELECT graph_status FROM resolved_case WHERE id=?", String.class, caseId).equals("SUCCEEDED"));
        var after = json(jdbc.queryForObject("SELECT source_metadata::text FROM knowledge_document_version WHERE source_metadata->>'caseId'=?", String.class, caseId.toString()));
        assertThat(after.path("indexRequests").path("VECTOR")).isEqualTo(json(before).path("indexRequests").path("VECTOR"));
        assertThat(after.path("indexRequests").path("NEO4J").path("attempt").asInt()).isEqualTo(2);
        assertThat(caseDetail().path("publication").path("canSearch").asBoolean()).isTrue();
    }
    @Test void 권한회수후_멱등재확인과_CASE구독을_거부한다() throws Exception {
        var command = edit(); result(command(staff, "/cases/" + caseId + "/versions", command));
        jdbc.update("UPDATE it_system SET primary_owner_member_id=? WHERE id=?", other, system);
        mvc.perform(command(staff, "/cases/" + caseId + "/versions", command)).andExpect(status().isForbidden());
        mvc.perform(as(staff, get("/api/v2/realtime/events?targetType=CASE&targetId=" + caseId))).andExpect(status().isForbidden());
    }
    @Test void 동시편집은_한건만_저장한다() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var latch = new CountDownLatch(1);
            Callable<Integer> change = () -> { latch.await(); return mvc.perform(command(staff, "/cases/" + caseId + "/versions", edit())).andReturn().getResponse().getStatus(); };
            var a = pool.submit(change); var b = pool.submit(change); latch.countDown();
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(caseDetail().path("version").path("caseVersion").asInt()).isEqualTo(2);
    }

    @Test void UNKNOWN은_새시도를_만들지_않으며_GET은_색인을_시작하지_않는다() throws Exception {
        graph.set("UNKNOWN"); resolve("RESOLVED"); approve();
        await(() -> jdbc.queryForObject("SELECT count(*) FROM knowledge_document_version WHERE source_metadata->>'caseId'=? "
                + "AND source_metadata->'indexResults'->'NEO4J'->>'state'='UNKNOWN'", Integer.class, caseId.toString()) == 1);
        mvc.perform(command(staff, "/cases/" + caseId + "/index-retries", basic(1).put("store", "NEO4J")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INDEX_NOT_RETRYABLE"));
        clearInvocations(indexClient);
        assertThat(caseDetail().path("publication").path("canSearch").asBoolean()).isFalse();
        verifyNoInteractions(indexClient);
        result(command(staff, "/cases/" + caseId + "/index-checks", basic(1).put("store", "NEO4J")));
        await(() -> { try { verify(indexClient).send(any(), eq(true)); return true; } catch (AssertionError pending) { return false; } });
        verify(indexClient, never()).send(any(), eq(false));
    }

    @Test void 승인요청_보존실패는_승인과_문서까지_롤백한다() throws Exception {
        resolve("RESOLVED");
        jdbc.execute("CREATE FUNCTION case170_reject_document() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic test failure'; END $$");
        jdbc.execute("CREATE TRIGGER case170_reject_document BEFORE INSERT ON knowledge_document_version FOR EACH ROW EXECUTE FUNCTION case170_reject_document()");
        try {
            mvc.perform(command(staff, "/cases/" + caseId + "/approvals", basic(1))).andExpect(status().is5xxServerError());
            assertThat(caseDetail().path("approval").isNull()).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_document WHERE source_data->>'caseId'=?", Integer.class, caseId.toString())).isZero();
            verifyNoInteractions(indexClient);
        } finally {
            jdbc.execute("DROP TRIGGER case170_reject_document ON knowledge_document_version");
            jdbc.execute("DROP FUNCTION case170_reject_document()");
        }
    }

    @Test void 검색권한_gate는_양쪽반영과_현재권한을_확인한다() throws Exception {
        graph.set("FAILED"); resolve("RESOLVED"); approve();
        await(() -> jdbc.queryForObject("SELECT graph_status FROM resolved_case WHERE id=?", String.class, caseId).equals("FAILED"));
        var runId = jdbc.queryForObject("SELECT id FROM agent_run WHERE trigger_work_log_id=?", UUID.class, record);
        var candidate = new com.hihops.hops.agent.internal.KnowledgeAuthorizationController.Candidate(caseId, 1,
                com.hihops.hops.agent.internal.KnowledgeAuthorizationController.Kind.CASE);
        var command = new com.hihops.hops.agent.internal.KnowledgeAuthorizationController.Command("hops-system-1", UUID.randomUUID(), runId, 1, List.of(candidate));
        assertThat(authorizations.authorize(command).path("allowedRefs").isEmpty()).isTrue();
        graph.set("SUCCEEDED"); result(command(staff, "/cases/" + caseId + "/index-retries", basic(1).put("store", "NEO4J")));
        await(() -> jdbc.queryForObject("SELECT graph_status FROM resolved_case WHERE id=?", String.class, caseId).equals("SUCCEEDED"));
        assertThat(authorizations.authorize(command).path("allowedRefs").size()).isEqualTo(1);
        jdbc.update("UPDATE member SET policy_revision=policy_revision+1 WHERE id=?", staff);
        assertThatThrownBy(() -> authorizations.authorize(command)).isInstanceOf(com.hihops.hops.global.exception.BusinessException.class);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "PRODUCTION,false,OPERATIONAL", "SYNTHETIC,true,SYNTHETIC", "DEMO_VERIFIED,true,DEMO_VERIFIED"
    })
    void 승인_문서의_합성여부는_사례출처와_일치한다(String origin, boolean synthetic, String wireOrigin) throws Exception {
        jdbc.update("UPDATE resolved_case SET origin_type=? WHERE id=?", origin, caseId);
        resolve("RESOLVED"); approve();
        assertThat(jdbc.queryForObject("SELECT is_synthetic FROM knowledge_document WHERE source_data->>'caseId'=?",
                Boolean.class, caseId.toString())).isEqualTo(synthetic);
        var version = json(jdbc.queryForObject("SELECT content FROM knowledge_document_version WHERE source_metadata->>'caseId'=?",
                String.class, caseId.toString()));
        assertThat(version.path("approvedCase").path("origin").asString()).isEqualTo(wireOrigin);
        assertThat(caseDetail().path("version").path("origin").asString()).isEqualTo(wireOrigin);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "problemStatement,가", "action,가", "verification,가", "applicability,가",
        "problemStatement,😀", "action,😀", "verification,😀", "applicability,😀"
    })
    void 본문_4001코드포인트는_편집과_승인문서_저장전에_거부한다(String field, String character) throws Exception {
        var command = edit().put(field, character.repeat(4001));
        mvc.perform(command(staff, "/cases/" + caseId + "/versions", command)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        var value = caseDetail();
        assertThat(value.path("version").path("caseVersion").asInt()).isEqualTo(1);
        assertThat(value.path("version").path("state").asString()).isEqualTo("DRAFT");
        assertThat(value.path("approval").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_document WHERE source_data->>'caseId'=?",
                Integer.class, caseId.toString())).isZero();
        verifyNoInteractions(indexClient);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "problemStatement,가", "action,가", "verification,가", "applicability,가",
        "problemStatement,😀", "action,😀", "verification,😀", "applicability,😀"
    })
    void 본문_4000코드포인트는_잘라내지_않고_승인문서에_보존한다(String field, String character) throws Exception {
        String content = character.repeat(4000);
        result(command(staff, "/cases/" + caseId + "/versions", edit().put(field, content)));
        resolve("RESOLVED");
        result(command(staff, "/cases/" + caseId + "/approvals", basic(2)));
        var version = json(jdbc.queryForObject("SELECT content FROM knowledge_document_version WHERE source_metadata->>'caseId'=?",
                String.class, caseId.toString()));
        assertThat(version.path("approvedCase").path(field).asString()).isEqualTo(content);
    }
}
