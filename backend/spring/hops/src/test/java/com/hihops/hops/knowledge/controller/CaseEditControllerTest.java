package com.hihops.hops.knowledge.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 사례 초안 수정 (F011-CONTRACT-04, WIRE12 POST /cases/{caseId}/versions, DATA_TYPES CaseEditCommand).
 *
 * <p>현재 DRAFT를 고치면 같은 사례의 version_no가 오른다. 승인된 사례는 고칠 수 없고, 출처·원 기록·적용 시스템은 바꾸지 않는다.
 * 초안 준비는 {@link CaseDraftFixture}를 따른다.
 */
class CaseEditControllerTest extends CaseDraftFixture {

    // ---------- 성공 ----------

    @Test
    void 초안을_수정하면_같은_사례의_버전이_오르고_수정_내용이_상세에_반영된다() throws Exception {
        UUID caseId = draft();
        JsonNode before = result(caseDetail(staff, caseId)).path("version");

        JsonNode receipt = result(versions(staff, caseId, edit()));
        assertThat(receipt.path("state").asString()).isEqualTo("SUCCEEDED");
        assertThat(receipt.path("resource").path("type").asString()).isEqualTo("CASE");
        assertThat(receipt.path("resource").path("id").asString()).isEqualTo(caseId.toString());
        assertThat(receipt.path("error").isNull()).isTrue();

        JsonNode body = result(caseDetail(staff, caseId));
        JsonNode version = body.path("version");
        assertThat(version.path("id").asString()).isEqualTo(caseId.toString());
        assertThat(version.path("caseVersion").asInt()).isEqualTo(2);
        assertThat(version.path("state").asString()).isEqualTo("DRAFT");
        assertThat(version.path("problemStatement").asString()).isEqualTo("수정한 문제 설명");
        assertThat(version.path("title").asString()).isEqualTo("수정한 제목");
        assertThat(version.path("action").asString()).isEqualTo("수정한 조치");
        assertThat(version.path("verification").asString()).isEqualTo("수정한 검증");
        assertThat(version.path("applicability").asString()).isEqualTo("수정한 적용 조건");
        assertThat(version.path("limitations")).isEqualTo(mapper.valueToTree(List.of("제한 하나", "제한 둘")));
        assertThat(version.path("appliesToSystemVersion").asString()).isEqualTo("2.1");
        assertThat(version.path("accessScope").asString()).isEqualTo("EMPLOYEE");
        assertThat(body.path("publication").path("caseVersion").asInt()).isEqualTo(2);
        assertThat(body.path("publication").path("state").asString()).isEqualTo("PRIVATE");

        // 출처·원 기록·적용 시스템·확인 상태는 이 API로 바뀌지 않는다.
        for (String field : List.of("ticketId", "problemId", "workRecordId", "resolutionRequestId", "systemId", "caseType",
                "confirmedCause", "origin", "confirmationOrigin", "sourceMessageIds", "createdAt")) {
            assertThat(version.path(field)).as(field).isEqualTo(before.path(field));
        }
    }

    @Test
    void 적용_버전이_null이고_공개범위가_직원_전용이_아니어도_수정된다() throws Exception {
        UUID caseId = draft();
        ObjectNode command = edit();
        command.putNull("appliesToSystemVersion");
        command.put("accessScope", "SYSTEM_STAFF");
        result(versions(staff, caseId, command));

        JsonNode version = result(caseDetail(staff, caseId)).path("version");
        assertThat(version.path("appliesToSystemVersion").isNull()).isTrue();
        assertThat(version.path("accessScope").asString()).isEqualTo("SYSTEM_STAFF");
        assertThat(stored(caseId)).containsEntry("access_level", "STAFF").containsEntry("applied_version", null);
    }

    @Test
    void 공개범위_EMPLOYEE는_저장_행에서_EMPLOYEE로_남는다() throws Exception {
        UUID caseId = draft();
        result(versions(staff, caseId, edit()));
        assertThat(stored(caseId)).containsEntry("access_level", "EMPLOYEE").containsEntry("version_no", 2)
                .containsEntry("case_status", "DRAFT");
    }

    @Test
    void 제한_조건만_바꾸고_Curator가_보존한_확인_사실과_출처는_그대로_둔다() throws Exception {
        UUID caseId = draft();
        JsonNode before = mapper.readTree(stored(caseId).get("confirmed_facts").toString());
        JsonNode relationsBefore = mapper.readTree(stored(caseId).get("relation_candidates").toString());
        assertThat(before.propertyNames()).contains("facts", "limitations", "sourceMessageIds", "knowledge");

        result(versions(staff, caseId, edit()));

        JsonNode after = mapper.readTree(stored(caseId).get("confirmed_facts").toString());
        assertThat(after.path("limitations")).isEqualTo(mapper.valueToTree(List.of("제한 하나", "제한 둘")));
        for (String field : List.of("facts", "sourceMessageIds", "knowledge")) {
            assertThat(after.path(field)).as(field).isEqualTo(before.path(field));
        }
        assertThat(new ArrayList<>(after.propertyNames())).containsExactlyInAnyOrderElementsOf(
                new ArrayList<>(before.propertyNames()));
        assertThat(mapper.readTree(stored(caseId).get("relation_candidates").toString())).isEqualTo(relationsBefore);
    }

    @Test
    void 사례만_바뀌고_원_처리기록과_확인된_원인은_그대로다() throws Exception {
        UUID caseId = draft();
        JsonNode recordBefore = detail().path("workRecords").get(0);
        Map<String, Object> before = stored(caseId);

        result(versions(staff, caseId, edit()));

        JsonNode recordAfter = detail().path("workRecords").get(0);
        assertThat(recordAfter).isEqualTo(recordBefore);
        assertThat(recordAfter.path("action").asString()).isEqualTo("실제 조치");
        Map<String, Object> after = stored(caseId);
        for (String column : List.of("source_work_log_id", "it_system_id", "case_type", "confirmed_cause", "origin_type",
                "curator_run_id", "case_number", "vector_status", "graph_status", "relation_candidates")) {
            assertThat(after.get(column)).as(column).isEqualTo(before.get(column));
        }
    }

    @Test
    void 수정하면_목록의_최신_버전과_갱신_시각이_바뀐다() throws Exception {
        UUID caseId = draft();
        jdbc.update("UPDATE resolved_case SET updated_at='2026-01-01T00:00:00Z' WHERE id=?", caseId);
        result(versions(staff, caseId, edit()));

        JsonNode item = result(cases(staff)).path("items").get(0);
        assertThat(item.path("id").asString()).isEqualTo(caseId.toString());
        assertThat(item.path("latestCaseVersion").asInt()).isEqualTo(2);
        assertThat(item.path("latestState").asString()).isEqualTo("DRAFT");
        assertThat(item.path("title").asString()).isEqualTo("수정한 제목");
        assertThat(item.path("updatedAt").asString()).isGreaterThan("2026-01-02");
    }

    @Test
    void 이어서_수정하려면_새_버전을_기대해야_하고_두번째_수정은_버전3이_된다() throws Exception {
        UUID caseId = draft();
        result(versions(staff, caseId, edit()));
        ObjectNode second = edit();
        second.put("expectedCaseVersion", 2).put("title", "두 번째 제목");
        result(versions(staff, caseId, second));
        JsonNode version = result(caseDetail(staff, caseId)).path("version");
        assertThat(version.path("caseVersion").asInt()).isEqualTo(3);
        assertThat(version.path("title").asString()).isEqualTo("두 번째 제목");
    }

    // ---------- 멱등성 ----------

    @Test
    void 같은_요청을_다시_보내면_처음_결과를_돌려주고_버전을_다시_올리지_않는다() throws Exception {
        UUID caseId = draft();
        ObjectNode command = edit();
        JsonNode first = result(versions(staff, caseId, command));
        JsonNode second = result(versions(staff, caseId, command));
        assertThat(second.path("operationId").asString()).isEqualTo(first.path("operationId").asString());
        assertThat(second.path("resource")).isEqualTo(first.path("resource"));
        assertThat(stored(caseId)).containsEntry("version_no", 2);
    }

    @Test
    void 같은_키로_다른_내용을_보내면_409_IDEMPOTENCY_KEY_REUSED이고_처음_수정이_유지된다() throws Exception {
        UUID caseId = draft();
        ObjectNode command = edit();
        result(versions(staff, caseId, command));
        ObjectNode changed = command.deepCopy();
        changed.put("title", "다른 제목");
        mvc.perform(versions(staff, caseId, changed)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(stored(caseId)).containsEntry("title", "수정한 제목").containsEntry("version_no", 2);
    }

    @Test
    void 버전_충돌_거절도_같은_키로_다시_보내면_같은_오류이고_사례는_바뀌지_않는다() throws Exception {
        UUID caseId = draft();
        result(versions(staff, caseId, edit()));
        ObjectNode stale = edit();
        stale.put("expectedCaseVersion", 1);
        mvc.perform(versions(staff, caseId, stale)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CASE_VERSION_CONFLICT"));
        mvc.perform(versions(staff, caseId, stale)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CASE_VERSION_CONFLICT"));
        assertThat(stored(caseId)).containsEntry("version_no", 2);
    }

    // ---------- 버전·상태 ----------

    @Test
    void 오래된_expectedCaseVersion은_409_CASE_VERSION_CONFLICT이고_아무것도_바꾸지_않는다() throws Exception {
        UUID caseId = draft();
        Map<String, Object> before = stored(caseId);
        ObjectNode stale = edit();
        stale.put("expectedCaseVersion", 5);
        mvc.perform(versions(staff, caseId, stale)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CASE_VERSION_CONFLICT"));
        assertThat(stored(caseId)).isEqualTo(before);
    }

    @Test
    void 승인된_사례는_409_INVALID_STATE이고_승인_내용을_바꾸지_않는다() throws Exception {
        UUID caseId = draft();
        jdbc.update("UPDATE resolved_case SET case_status='APPROVED',approved_by_member_id=?,approved_at=now() WHERE id=?",
                staff, caseId);
        Map<String, Object> before = stored(caseId);
        mvc.perform(versions(staff, caseId, edit())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        assertThat(stored(caseId)).isEqualTo(before);
    }

    @Test
    void 같은_버전을_기대한_두_수정이_동시에_오면_하나만_성공하고_하나는_버전_충돌이다() throws Exception {
        UUID caseId = draft();
        ObjectNode first = edit();
        first.put("title", "첫 번째 제목");
        ObjectNode second = edit();
        second.put("title", "두 번째 제목");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit(statusOf(start, versions(staff, caseId, first)));
            Future<Integer> b = pool.submit(statusOf(start, versions(staff, caseId, second)));
            start.countDown();
            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(stored(caseId)).containsEntry("version_no", 2);
        assertThat(stored(caseId).get("title")).isIn("첫 번째 제목", "두 번째 제목");
    }

    // ---------- 권한 ----------

    @Test
    void 현재_사례_시스템_담당자가_아니면_존재여부를_숨기고_404이며_바뀌지_않는다() throws Exception {
        UUID caseId = draft();
        Map<String, Object> before = stored(caseId);
        for (UUID user : List.of(other, requester, admin)) {
            mvc.perform(versions(user, caseId, edit())).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
        mvc.perform(versions(other, UUID.randomUUID(), edit())).andExpect(status().isNotFound());
        assertThat(stored(caseId)).isEqualTo(before);
    }

    @Test
    void 주담당자가_바뀌면_새_담당자만_수정할_수_있다() throws Exception {
        UUID caseId = draft();
        jdbc.update("UPDATE it_system SET primary_owner_member_id=? WHERE id=?", other, system);
        mvc.perform(versions(staff, caseId, edit())).andExpect(status().isNotFound());
        result(versions(other, caseId, edit()));
        assertThat(stored(caseId)).containsEntry("version_no", 2);
    }

    @Test
    void 이미_처리한_요청도_권한을_잃으면_같은_키_재확인이_404다() throws Exception {
        UUID caseId = draft();
        ObjectNode command = edit();
        result(versions(staff, caseId, command));
        jdbc.update("UPDATE it_system SET primary_owner_member_id=? WHERE id=?", other, system);
        mvc.perform(versions(staff, caseId, command)).andExpect(status().isNotFound());
    }

    @Test
    void 삭제된_사례는_404이고_바뀌지_않는다() throws Exception {
        UUID caseId = draft();
        jdbc.update("UPDATE resolved_case SET deleted_at=now() WHERE id=?", caseId);
        mvc.perform(versions(staff, caseId, edit())).andExpect(status().isNotFound());
        assertThat(stored(caseId)).containsEntry("version_no", 1).containsEntry("title", "가상 사례");
    }

    // ---------- 입력 검증 ----------

    @ParameterizedTest
    @ValueSource(strings = {"expectedCaseVersion", "problemStatement", "title", "action", "verification", "applicability",
            "limitations", "appliesToSystemVersion", "accessScope"})
    void 필수_필드가_없으면_400이고_바뀌지_않는다(String field) throws Exception {
        UUID caseId = draft();
        Map<String, Object> before = stored(caseId);
        ObjectNode command = edit();
        command.remove(field);
        invalid(caseId, command);
        assertThat(stored(caseId)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"problemStatement", "title", "action", "verification", "applicability"})
    void 공백뿐인_문자열은_400이다(String field) throws Exception {
        UUID caseId = draft();
        ObjectNode command = edit();
        command.put(field, "   ");
        invalid(caseId, command);
    }

    @ParameterizedTest
    @ValueSource(strings = {"problemStatement", "title", "action", "verification", "applicability"})
    void null_문자열은_400이다(String field) throws Exception {
        UUID caseId = draft();
        ObjectNode command = edit();
        command.putNull(field);
        invalid(caseId, command);
    }

    @Test
    void 제목은_200자까지_허용하고_201자는_400이다() throws Exception {
        UUID caseId = draft();
        ObjectNode longest = edit();
        longest.put("title", "가".repeat(200));
        result(versions(staff, caseId, longest));
        assertThat(stored(caseId).get("title")).isEqualTo("가".repeat(200));

        ObjectNode tooLong = edit();
        tooLong.put("expectedCaseVersion", 2).put("title", "가".repeat(201));
        invalid(caseId, tooLong);
        assertThat(stored(caseId)).containsEntry("version_no", 2);
    }

    @Test
    void 적용_버전은_100자까지_허용하고_101자나_공백은_400이다() throws Exception {
        UUID caseId = draft();
        ObjectNode longest = edit();
        longest.put("appliesToSystemVersion", "1".repeat(100));
        result(versions(staff, caseId, longest));

        ObjectNode tooLong = edit();
        tooLong.put("expectedCaseVersion", 2).put("appliesToSystemVersion", "1".repeat(101));
        invalid(caseId, tooLong);
        ObjectNode blank = edit();
        blank.put("expectedCaseVersion", 2).put("appliesToSystemVersion", " ");
        invalid(caseId, blank);
    }

    @Test
    void 제한_조건은_빈_배열을_허용한다() throws Exception {
        UUID caseId = draft();
        ObjectNode command = edit();
        command.putArray("limitations");
        result(versions(staff, caseId, command));
        assertThat(result(caseDetail(staff, caseId)).path("version").path("limitations")).isEmpty();
    }

    @Test
    void 제한_조건에_문자열이_아니거나_빈_항목이_섞이면_400이다() throws Exception {
        UUID caseId = draft();
        for (Object bad : new Object[] {"", "  ", 7, true}) {
            ObjectNode command = edit();
            var array = command.putArray("limitations");
            array.add("정상");
            if (bad instanceof String text) array.add(text);
            if (bad instanceof Integer number) array.add(number);
            if (bad instanceof Boolean flag) array.add(flag);
            invalid(caseId, command);
        }
        ObjectNode withNull = edit();
        withNull.putArray("limitations").add("정상").addNull();
        invalid(caseId, withNull);
        ObjectNode notArray = edit();
        notArray.put("limitations", "배열이 아님");
        invalid(caseId, notArray);
        ObjectNode nullList = edit();
        nullList.putNull("limitations");
        invalid(caseId, nullList);
        assertThat(stored(caseId)).containsEntry("version_no", 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUBLIC", "staff", "", "SYSTEM_OWNER"})
    void 정해진_공개범위가_아니면_400이다(String scope) throws Exception {
        UUID caseId = draft();
        ObjectNode command = edit();
        command.put("accessScope", scope);
        invalid(caseId, command);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void 기대_버전이_양수가_아니면_400이다(int version) throws Exception {
        UUID caseId = draft();
        ObjectNode command = edit();
        command.put("expectedCaseVersion", version);
        invalid(caseId, command);
    }

    @Test
    void 요청_키_헤더가_없거나_본문의_requestId와_다르면_400이다() throws Exception {
        UUID caseId = draft();
        ObjectNode command = edit();
        mvc.perform(as(staff, post("/api/v2/cases/" + caseId + "/versions").contentType("application/json")
                .content(command.toString()))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(as(staff, post("/api/v2/cases/" + caseId + "/versions").contentType("application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString()).content(command.toString())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        assertThat(stored(caseId)).containsEntry("version_no", 1);
    }

    @Test
    void 요청에_requestId가_없으면_400이다() throws Exception {
        UUID caseId = draft();
        ObjectNode command = edit();
        command.remove("requestId");
        mvc.perform(as(staff, post("/api/v2/cases/" + caseId + "/versions").contentType("application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString()).content(command.toString())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    // ---------- 준비 ----------

    private ObjectNode edit() {
        ObjectNode command = mapper.createObjectNode();
        command.put("requestId", UUID.randomUUID().toString());
        command.put("expectedCaseVersion", 1);
        command.put("problemStatement", "수정한 문제 설명");
        command.put("title", "수정한 제목");
        command.put("action", "수정한 조치");
        command.put("verification", "수정한 검증");
        command.put("applicability", "수정한 적용 조건");
        command.set("limitations", mapper.valueToTree(List.of("제한 하나", "제한 둘")));
        command.put("appliesToSystemVersion", "2.1");
        command.put("accessScope", "EMPLOYEE");
        return command;
    }

    private MockHttpServletRequestBuilder versions(UUID user, UUID caseId, JsonNode body) {
        return command(user, "/cases/" + caseId + "/versions", body);
    }

    private void invalid(UUID caseId, JsonNode body) throws Exception {
        mvc.perform(versions(staff, caseId, body)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    private Callable<Integer> statusOf(CountDownLatch start, MockHttpServletRequestBuilder request) {
        return () -> {
            start.await();
            return mvc.perform(request).andReturn().getResponse().getStatus();
        };
    }

    private Map<String, Object> stored(UUID caseId) {
        return jdbc.queryForMap("""
                SELECT version_no,case_status,title,problem_statement,action_detail,verification_result,applicability,
                    applied_version,access_level,confirmed_facts::text AS confirmed_facts,
                    relation_candidates::text AS relation_candidates,confirmed_cause,source_work_log_id,it_system_id,
                    case_type,origin_type,curator_run_id,case_number,vector_status,graph_status,approved_by_member_id,
                    approved_at
                FROM resolved_case WHERE id=?""", caseId);
    }

    private MockHttpServletRequestBuilder cases(UUID user) {
        return as(user, get("/api/v2/cases"));
    }

    private MockHttpServletRequestBuilder caseDetail(UUID user, UUID caseId) {
        return as(user, get("/api/v2/cases/" + caseId));
    }
}
