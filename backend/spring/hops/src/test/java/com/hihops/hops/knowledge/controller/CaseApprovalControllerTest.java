package com.hihops.hops.knowledge.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
 * 사례 승인 (F011-CONTRACT-02·05, WIRE12 POST /cases/{caseId}/approvals, DATA_TYPES CaseApproveCommand).
 *
 * <p>승인은 한 거래로 사례 승인, 검색 문서 버전, Vector·Neo4j 반영 요청(source_metadata.indexRequests)과 두 PENDING 상태를 저장한다
 * (contracts/agents I1.1). 승인했다고 바로 검색에 공개하지 않는다. 초안 준비는 {@link CaseDraftFixture}를 따른다.
 */
class CaseApprovalControllerTest extends CaseDraftFixture {

    // ---------- 성공 ----------

    @Test
    void 해결_확인된_초안을_승인하면_승인자와_시각이_남고_버전은_그대로이며_공개되지_않는다() throws Exception {
        UUID caseId = readyDraft();

        JsonNode receipt = result(approvals(staff, caseId, approve(1)));
        assertThat(receipt.path("state").asString()).isEqualTo("SUCCEEDED");
        assertThat(receipt.path("resource").path("type").asString()).isEqualTo("CASE");
        assertThat(receipt.path("resource").path("id").asString()).isEqualTo(caseId.toString());
        assertThat(receipt.path("error").isNull()).isTrue();

        JsonNode body = result(caseDetail(staff, caseId));
        assertThat(body.path("version").path("state").asString()).isEqualTo("APPROVED");
        assertThat(body.path("version").path("caseVersion").asInt()).isEqualTo(1);
        assertThat(body.path("approval").path("caseId").asString()).isEqualTo(caseId.toString());
        assertThat(body.path("approval").path("caseVersion").asInt()).isEqualTo(1);
        assertThat(body.path("approval").path("approvedBy").path("id").asString()).isEqualTo(staff.toString());
        assertThat(body.path("approval").path("approvedAt").asString()).isNotBlank();
        assertThat(body.path("publication").path("state").asString()).isEqualTo("PRIVATE");
        assertThat(body.path("publication").path("canSearch").asBoolean()).isFalse();

        Map<String, Object> stored = stored(caseId);
        assertThat(stored).containsEntry("case_status", "APPROVED").containsEntry("version_no", 1)
                .containsEntry("vector_status", "PENDING").containsEntry("graph_status", "PENDING");
        assertThat(stored.get("approved_by_member_id")).isEqualTo(staff);
        assertThat(stored.get("approved_at")).isNotNull();
    }

    @Test
    void 승인하면_상세에_저장소별_반영_시도가_대기로_보이고_목록은_승인_상태이며_공개_버전은_없다() throws Exception {
        UUID caseId = readyDraft();
        result(approvals(staff, caseId, approve(1)));

        JsonNode indexes = result(caseDetail(staff, caseId)).path("indexes");
        assertThat(indexes).hasSize(2);
        assertThat(indexes.findValuesAsString("store")).containsExactlyInAnyOrder("VECTOR", "NEO4J");
        for (JsonNode index : indexes) {
            assertThat(index.path("caseId").asString()).isEqualTo(caseId.toString());
            assertThat(index.path("caseVersion").asInt()).isEqualTo(1);
            assertThat(index.path("state").asString()).isEqualTo("PENDING");
            assertThat(index.path("attempt").asInt()).isEqualTo(1);
            assertThat(index.path("error").isNull()).isTrue();
            assertThat(UUID.fromString(index.path("jobId").asString())).isNotNull();
        }
        assertThat(indexes.get(0).path("jobId").asString()).isNotEqualTo(indexes.get(1).path("jobId").asString());

        JsonNode item = result(cases(staff)).path("items").get(0);
        assertThat(item.path("latestState").asString()).isEqualTo("APPROVED");
        assertThat(item.path("publishedVersions")).isEmpty();
    }

    @Test
    void 승인과_같은_거래로_현재_사례의_검색_문서_버전과_저장소별_요청을_보존한다() throws Exception {
        UUID caseId = readyDraft();
        result(approvals(staff, caseId, approve(1)));

        Map<String, Object> document = jdbc.queryForMap("""
                SELECT d.id,d.title,d.document_type,d.it_system_id,d.access_level,d.data_split,d.review_status
                FROM knowledge_document d JOIN knowledge_document_version v ON v.knowledge_document_id=d.id
                WHERE v.source_metadata ->> 'caseId' = ?""", caseId.toString());
        assertThat(document).containsEntry("title", "가상 사례").containsEntry("document_type", "CASE")
                .containsEntry("access_level", "STAFF").containsEntry("data_split", "RAG")
                .containsEntry("review_status", "APPROVED");
        assertThat(document.get("it_system_id")).isEqualTo(system);

        Map<String, Object> version = jdbc.queryForMap("""
                SELECT version_no,review_status,content_digest,source_metadata::text AS metadata
                FROM knowledge_document_version WHERE knowledge_document_id=?""", document.get("id"));
        assertThat(version).containsEntry("version_no", 1).containsEntry("review_status", "APPROVED");
        assertThat(version.get("content_digest").toString()).matches("[0-9a-f]{64}");

        JsonNode metadata = mapper.readTree(version.get("metadata").toString());
        assertThat(metadata.path("caseId").asString()).isEqualTo(caseId.toString());
        assertThat(metadata.path("caseVersion").asInt()).isEqualTo(1);
        JsonNode requests = metadata.path("indexRequests");
        assertThat(new java.util.ArrayList<>(requests.propertyNames())).containsExactlyInAnyOrder("VECTOR", "NEO4J");
        JsonNode indexes = result(caseDetail(staff, caseId)).path("indexes");
        for (String store : List.of("VECTOR", "NEO4J")) {
            JsonNode request = requests.path(store);
            assertThat(request.path("contractVersion").asString()).isEqualTo("hops-system-1");
            assertThat(request.path("caseId").asString()).isEqualTo(caseId.toString());
            assertThat(request.path("caseVersion").asInt()).isEqualTo(1);
            assertThat(request.path("store").asString()).isEqualTo(store);
            assertThat(request.path("attempt").asInt()).isEqualTo(1);
            assertThat(request.path("contentDigest").asString()).matches("[0-9a-f]{64}");
            // 상세의 반영 시도는 같은 jobId를 보여 준다.
            for (JsonNode index : indexes) {
                if (store.equals(index.path("store").asString())) {
                    assertThat(index.path("jobId").asString()).isEqualTo(request.path("jobId").asString());
                }
            }
        }
        assertThat(requests.path("VECTOR").path("jobId").asString()).isNotEqualTo(requests.path("NEO4J").path("jobId").asString());
        // 저장소별 digest는 서로 다르지만 approvedCase는 같다.
        assertThat(requests.path("VECTOR").path("contentDigest").asString())
                .isNotEqualTo(requests.path("NEO4J").path("contentDigest").asString());
        assertThat(requests.path("VECTOR").path("approvedCase")).isEqualTo(requests.path("NEO4J").path("approvedCase"));
    }

    @Test
    void 반영_요청의_승인_사례_본문은_계약_필드를_저장된_사례에서_채운다() throws Exception {
        UUID caseId = readyDraft();
        JsonNode work = detail().path("workRecords").get(0);
        String resolutionId = detail().path("activeResolutionRequest").path("id").asString();
        result(approvals(staff, caseId, approve(1)));

        JsonNode body = indexRequest(caseId, "VECTOR").path("approvedCase");
        assertThat(body.path("workRecordId").asString()).isEqualTo(work.path("id").asString());
        assertThat(body.path("resolutionRequestId").asString()).isEqualTo(resolutionId);
        assertThat(body.path("approvedBy").asString()).isEqualTo(staff.toString());
        assertThat(body.path("approvedAt").asString()).isNotBlank();
        assertThat(body.path("title").asString()).isEqualTo("가상 사례");
        assertThat(body.path("problemStatement").asString()).isEqualTo("가상 문제");
        assertThat(body.path("action").asString()).isEqualTo("실제 조치");
        assertThat(body.path("verification").asString()).isEqualTo("정상 동작 확인");
        assertThat(body.path("applicability").asString()).isEqualTo("가상 환경 전용");
        assertThat(body.path("limitations")).isEqualTo(mapper.valueToTree(LIMITATIONS));
        assertThat(body.path("appliesToSystemVersion").isNull()).isTrue();
        assertThat(body.path("accessScope").asString()).isEqualTo("SYSTEM_STAFF");
        assertThat(body.path("origin").asString()).isIn("SYNTHETIC", "DEMO_VERIFIED", "OPERATIONAL");
        assertThat(body.path("confirmationOrigin").asString()).isIn("SYNTHETIC_PRESET", "HOPS_USER");
        assertThat(body.path("sourceMessageIds")).isEqualTo(mapper.valueToTree(List.of(message.toString())));
        assertThat(body.path("entityLinks")).hasSize(1);
        assertThat(body.path("entityLinks").get(0).path("targetId").asString()).isEqualTo(system.toString());
        assertThat(body.path("systemId").asString()).isEqualTo(system.toString());
        assertThat(body.path("caseType").asString()).isEqualTo("RESOLUTION");
        assertThat(body.has("confirmedCause")).isTrue();
    }

    @Test
    void 저장소별_digest는_계약의_정렬된_JSON_SHA256과_같다() throws Exception {
        UUID caseId = readyDraft();
        result(approvals(staff, caseId, approve(1)));

        for (String store : List.of("VECTOR", "NEO4J")) {
            JsonNode request = indexRequest(caseId, store);
            ObjectNode subject = mapper.createObjectNode();
            subject.put("caseId", caseId.toString()).put("caseVersion", 1).put("store", store);
            subject.set("approvedCase", request.path("approvedCase"));
            String canonical = mapper.writeValueAsString(sorted(mapper.convertValue(subject, Object.class)));
            String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
            assertThat(request.path("contentDigest").asString()).as(store).isEqualTo(expected);
        }
    }

    @Test
    void 수정한_내용이_승인되고_반영_요청에도_수정_뒤_버전이_들어간다() throws Exception {
        UUID caseId = readyDraft();
        ObjectNode edit = mapper.createObjectNode();
        edit.put("requestId", UUID.randomUUID().toString()).put("expectedCaseVersion", 1)
                .put("problemStatement", "수정한 문제").put("title", "수정한 제목").put("action", "수정한 조치")
                .put("verification", "수정한 검증").put("applicability", "수정한 조건").put("accessScope", "EMPLOYEE")
                .put("appliesToSystemVersion", "2.1");
        edit.set("limitations", mapper.valueToTree(List.of("수정한 제한")));
        result(command(staff, "/cases/" + caseId + "/versions", edit));

        result(approvals(staff, caseId, approve(2)));

        JsonNode request = indexRequest(caseId, "NEO4J");
        assertThat(request.path("caseVersion").asInt()).isEqualTo(2);
        assertThat(request.path("approvedCase").path("title").asString()).isEqualTo("수정한 제목");
        assertThat(request.path("approvedCase").path("action").asString()).isEqualTo("수정한 조치");
        assertThat(request.path("approvedCase").path("accessScope").asString()).isEqualTo("EMPLOYEE");
        assertThat(request.path("approvedCase").path("appliesToSystemVersion").asString()).isEqualTo("2.1");
        assertThat(jdbc.queryForObject("SELECT access_level FROM knowledge_document d WHERE d.document_type='CASE' "
                + "AND d.it_system_id=?", String.class, system)).isEqualTo("EMPLOYEE");
        assertThat(jdbc.queryForObject("SELECT v.version_no FROM knowledge_document_version v WHERE "
                + "v.source_metadata ->> 'caseId' = ?", Integer.class, caseId.toString())).isEqualTo(2);
    }

    @Test
    void 승인은_원_처리_기록과_출처_필드를_바꾸지_않는다() throws Exception {
        UUID caseId = readyDraft();
        JsonNode recordBefore = detail().path("workRecords").get(0);
        Map<String, Object> before = stored(caseId);
        result(approvals(staff, caseId, approve(1)));

        assertThat(detail().path("workRecords").get(0)).isEqualTo(recordBefore);
        Map<String, Object> after = stored(caseId);
        for (String column : List.of("source_work_log_id", "it_system_id", "case_type", "confirmed_cause", "origin_type",
                "curator_run_id", "case_number", "title", "problem_statement", "action_detail", "verification_result",
                "applicability", "applied_version", "access_level", "confirmed_facts", "relation_candidates")) {
            assertThat(after.get(column)).as(column).isEqualTo(before.get(column));
        }
    }

    // ---------- 멱등성 ----------

    @Test
    void 같은_요청을_다시_보내면_처음_결과를_돌려주고_승인과_검색_문서를_다시_만들지_않는다() throws Exception {
        UUID caseId = readyDraft();
        ObjectNode command = approve(1);
        JsonNode first = result(approvals(staff, caseId, command));
        Map<String, Object> afterFirst = stored(caseId);
        JsonNode requestsFirst = indexRequest(caseId, "VECTOR");

        JsonNode second = result(approvals(staff, caseId, command));
        assertThat(second.path("operationId").asString()).isEqualTo(first.path("operationId").asString());
        assertThat(second.path("resource")).isEqualTo(first.path("resource"));
        assertThat(stored(caseId)).isEqualTo(afterFirst);
        assertThat(documentCount()).isEqualTo(1);
        assertThat(indexRequest(caseId, "VECTOR")).isEqualTo(requestsFirst);
    }

    @Test
    void 같은_키로_다른_내용을_보내면_409_IDEMPOTENCY_KEY_REUSED이다() throws Exception {
        UUID caseId = readyDraft();
        ObjectNode command = approve(1);
        result(approvals(staff, caseId, command));
        ObjectNode changed = command.deepCopy();
        changed.put("expectedCaseVersion", 2);
        mvc.perform(approvals(staff, caseId, changed)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(stored(caseId)).containsEntry("case_status", "APPROVED");
    }

    // ---------- 버전·상태·전제 ----------

    @Test
    void 오래된_expectedCaseVersion은_409_CASE_VERSION_CONFLICT이고_아무것도_저장하지_않는다() throws Exception {
        UUID caseId = readyDraft();
        Map<String, Object> before = stored(caseId);
        mvc.perform(approvals(staff, caseId, approve(5))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CASE_VERSION_CONFLICT"));
        assertThat(stored(caseId)).isEqualTo(before);
        assertThat(documentCount()).isZero();
    }

    @Test
    void 이미_승인된_사례를_다시_승인하면_409_INVALID_STATE이고_승인_내용과_요청을_바꾸지_않는다() throws Exception {
        UUID caseId = readyDraft();
        result(approvals(staff, caseId, approve(1)));
        Map<String, Object> before = stored(caseId);
        JsonNode request = indexRequest(caseId, "VECTOR");

        mvc.perform(approvals(staff, caseId, approve(1))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        assertThat(stored(caseId)).isEqualTo(before);
        assertThat(documentCount()).isEqualTo(1);
        assertThat(indexRequest(caseId, "VECTOR")).isEqualTo(request);
    }

    @Test
    void 문의자가_아직_확인하지_않았으면_409_APPROVAL_PRECONDITION_FAILED이고_초안으로_남는다() throws Exception {
        UUID caseId = draft();
        Map<String, Object> before = stored(caseId);
        mvc.perform(approvals(staff, caseId, approve(1))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPROVAL_PRECONDITION_FAILED"));
        assertThat(stored(caseId)).isEqualTo(before).containsEntry("case_status", "DRAFT");
        assertThat(documentCount()).isZero();
    }

    @Test
    void 문의자가_해결되지_않았다고_답했으면_409_APPROVAL_PRECONDITION_FAILED다() throws Exception {
        UUID caseId = draft();
        String resolutionId = detail().path("activeResolutionRequest").path("id").asString();
        result(command(requester, "/resolution-requests/" + resolutionId + "/responses", mapper.createObjectNode()
                .put("requestId", UUID.randomUUID().toString()).put("expectedRequestRevision", 1)
                .put("decision", "UNRESOLVED")));
        mvc.perform(approvals(staff, caseId, approve(1))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPROVAL_PRECONDITION_FAILED"));
        assertThat(stored(caseId)).containsEntry("case_status", "DRAFT");
        assertThat(documentCount()).isZero();
    }

    @Test
    void 버전_충돌을_승인_전제보다_먼저_본다() throws Exception {
        UUID caseId = draft();
        mvc.perform(approvals(staff, caseId, approve(9))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CASE_VERSION_CONFLICT"));
    }

    @Test
    void 같은_사례를_동시에_승인하면_하나만_성공하고_검색_문서도_하나다() throws Exception {
        UUID caseId = readyDraft();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit(statusOf(start, approvals(staff, caseId, approve(1))));
            Future<Integer> b = pool.submit(statusOf(start, approvals(staff, caseId, approve(1))));
            start.countDown();
            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(stored(caseId)).containsEntry("case_status", "APPROVED");
        assertThat(documentCount()).isEqualTo(1);
    }

    // ---------- 권한 ----------

    @Test
    void 현재_사례_시스템_담당자가_아니면_존재여부를_숨기고_404이며_저장하지_않는다() throws Exception {
        UUID caseId = readyDraft();
        Map<String, Object> before = stored(caseId);
        for (UUID user : List.of(other, requester, admin)) {
            mvc.perform(approvals(user, caseId, approve(1))).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
        mvc.perform(approvals(other, UUID.randomUUID(), approve(1))).andExpect(status().isNotFound());
        assertThat(stored(caseId)).isEqualTo(before);
        assertThat(documentCount()).isZero();
    }

    @Test
    void 주담당자가_바뀌면_새_담당자만_승인할_수_있고_승인자로_남는다() throws Exception {
        UUID caseId = readyDraft();
        jdbc.update("UPDATE it_system SET primary_owner_member_id=? WHERE id=?", other, system);
        mvc.perform(approvals(staff, caseId, approve(1))).andExpect(status().isNotFound());
        result(approvals(other, caseId, approve(1)));
        assertThat(stored(caseId).get("approved_by_member_id")).isEqualTo(other);
    }

    @Test
    void 이미_처리한_요청도_권한을_잃으면_같은_키_재확인이_404다() throws Exception {
        UUID caseId = readyDraft();
        ObjectNode command = approve(1);
        result(approvals(staff, caseId, command));
        jdbc.update("UPDATE it_system SET primary_owner_member_id=? WHERE id=?", other, system);
        mvc.perform(approvals(staff, caseId, command)).andExpect(status().isNotFound());
    }

    @Test
    void 삭제된_사례는_404이고_저장하지_않는다() throws Exception {
        UUID caseId = readyDraft();
        jdbc.update("UPDATE resolved_case SET deleted_at=now() WHERE id=?", caseId);
        mvc.perform(approvals(staff, caseId, approve(1))).andExpect(status().isNotFound());
        assertThat(stored(caseId)).containsEntry("case_status", "DRAFT");
        assertThat(documentCount()).isZero();
    }

    // ---------- 입력 검증 ----------

    @ParameterizedTest
    @ValueSource(strings = {"requestId", "expectedCaseVersion"})
    void 필수_필드가_없으면_400이고_저장하지_않는다(String field) throws Exception {
        UUID caseId = readyDraft();
        ObjectNode command = approve(1);
        command.remove(field);
        mvc.perform(as(staff, post("/api/v2/cases/" + caseId + "/approvals").contentType("application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString()).content(command.toString())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        assertThat(stored(caseId)).containsEntry("case_status", "DRAFT");
        assertThat(documentCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void 기대_버전이_양수가_아니면_400이다(int version) throws Exception {
        UUID caseId = readyDraft();
        mvc.perform(approvals(staff, caseId, approve(version))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void 요청_키_헤더가_없거나_본문의_requestId와_다르면_400이다() throws Exception {
        UUID caseId = readyDraft();
        ObjectNode command = approve(1);
        mvc.perform(as(staff, post("/api/v2/cases/" + caseId + "/approvals").contentType("application/json")
                .content(command.toString()))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(as(staff, post("/api/v2/cases/" + caseId + "/approvals").contentType("application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString()).content(command.toString())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        assertThat(stored(caseId)).containsEntry("case_status", "DRAFT");
    }

    // ---------- 준비 ----------

    private ObjectNode approve(int expectedCaseVersion) {
        return mapper.createObjectNode().put("requestId", UUID.randomUUID().toString())
                .put("expectedCaseVersion", expectedCaseVersion);
    }

    private MockHttpServletRequestBuilder approvals(UUID user, UUID caseId, JsonNode body) {
        return command(user, "/cases/" + caseId + "/approvals", body);
    }

    private JsonNode indexRequest(UUID caseId, String store) throws Exception {
        String metadata = jdbc.queryForObject(
                "SELECT source_metadata::text FROM knowledge_document_version WHERE source_metadata ->> 'caseId' = ?",
                String.class, caseId.toString());
        return mapper.readTree(metadata).path("indexRequests").path(store);
    }

    private int documentCount() {
        return jdbc.queryForObject("SELECT count(*) FROM knowledge_document WHERE document_type='CASE' AND it_system_id IN (?,?)",
                Integer.class, system, otherSystem);
    }

    /** 계약의 digest 입력: 키 오름차순 객체, 배열 순서 유지. */
    private static Object sorted(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            map.forEach((key, item) -> result.put(key.toString(), sorted(item)));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(CaseApprovalControllerTest::sorted).toList();
        return value;
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
