package com.hihops.hops.knowledge.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.hihops.hops.agent.client.AgentRunClient;
import com.hihops.hops.ticket.controller.TicketCommandFixture;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 사례 검토 목록·상세 조회 (F011, WIRE12 GET /cases·GET /cases/{caseId}, DATA_TYPES CaseSummary·CaseDetail).
 *
 * <p>사례 초안은 실제 처리 기록 저장 → Curator 결과 처리(CurationResultHandler) 흐름으로 만든다.
 * 조회는 그 저장 형식(confirmed_facts·relation_candidates)을 그대로 읽어야 하므로 테스트에서 행을 직접 넣지 않는다.
 * AI 서버만 가짜 응답으로 바꾼다.
 */
@SpringBootTest(properties = "hops.agent.dispatch-enabled=true")
class CaseControllerTest extends TicketCommandFixture {
    private static final List<String> LIMITATIONS = List.of("관리자 권한 필요", "구버전에는 적용 불가");

    @MockitoBean
    private AgentRunClient client;

    @BeforeEach
    void 문의자_원문() {
        // Curator는 해당 문제의 USER 원문만 사례 출처로 인정한다.
        var payload = mapper.createObjectNode();
        payload.putArray("problemLinks").addObject().put("problemId", problem.toString()).put("revision", 1)
                .putArray("spans").addObject().put("start", 0).put("end", 5);
        jdbc.update("UPDATE message SET sender_type='USER',content='결재 오류',payload=?::jsonb WHERE id=?",
                payload.toString(), message);
    }

    // ---------- 목록 ----------

    @Test
    void 목록은_내가_주담당인_시스템의_초안을_CaseSummary로_반환한다() throws Exception {
        UUID caseId = draft();
        JsonNode page = result(cases(staff));
        assertThat(page.path("page").asInt()).isZero();
        assertThat(page.path("size").asInt()).isEqualTo(20);
        assertThat(page.path("totalElements").asLong()).isEqualTo(1);
        JsonNode item = only(page, caseId);
        assertThat(item.path("title").asString()).isEqualTo("가상 사례");
        assertThat(item.path("systemId").asString()).isEqualTo(system.toString());
        assertThat(item.path("latestCaseVersion").asInt()).isEqualTo(1);
        assertThat(item.path("latestState").asString()).isEqualTo("DRAFT");
        assertThat(item.path("publishedVersions").isArray()).isTrue();
        assertThat(item.path("publishedVersions")).isEmpty();
        assertThat(item.path("generationState").asString()).isEqualTo("SUCCEEDED");
        assertThat(item.path("updatedAt").asString()).isNotBlank();
        assertThat(fieldNames(item)).containsExactlyInAnyOrder("id", "title", "systemId", "latestCaseVersion",
                "latestState", "publishedVersions", "generationState", "updatedAt");
    }

    @Test
    void 다른_시스템의_주담당자_목록에는_내_시스템_사례가_없다() throws Exception {
        UUID caseId = draft();
        assertThat(ids(result(cases(other)))).doesNotContain(caseId.toString());
    }

    @Test
    void 다른_시스템을_systemId로_지정하면_403이다() throws Exception {
        draft();
        mvc.perform(cases(staff).param("systemId", otherSystem.toString()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void 담당_시스템이_없는_사용자는_목록을_볼_수_없다() throws Exception {
        draft();
        mvc.perform(cases(requester)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void 내_시스템을_systemId로_지정하면_그_시스템_사례만_반환한다() throws Exception {
        UUID caseId = draft();
        assertThat(ids(result(cases(staff).param("systemId", system.toString())))).containsExactly(caseId.toString());
    }

    @Test
    void state로_초안과_승인을_구분해_거른다() throws Exception {
        UUID caseId = draft();
        assertThat(ids(result(cases(staff).param("state", "DRAFT")))).containsExactly(caseId.toString());
        assertThat(ids(result(cases(staff).param("state", "APPROVED")))).isEmpty();
    }

    @Test
    void 마지막_페이지를_넘기면_오류없이_빈_목록이다() throws Exception {
        draft();
        JsonNode page = result(cases(staff).param("page", "1").param("size", "1"));
        assertThat(page.path("items")).isEmpty();
        assertThat(page.path("page").asInt()).isEqualTo(1);
        assertThat(page.path("size").asInt()).isEqualTo(1);
        assertThat(page.path("totalElements").asLong()).isEqualTo(1);
    }

    @Test
    void 목록은_updatedAt_내림차순이고_같으면_id_내림차순이다() throws Exception {
        UUID first = draft();
        UUID second = copyDraft(first);
        jdbc.update("UPDATE resolved_case SET updated_at='2026-01-01T00:00:00Z' WHERE id=?", first);
        jdbc.update("UPDATE resolved_case SET updated_at='2026-01-02T00:00:00Z' WHERE id=?", second);
        assertThat(ids(result(cases(staff)))).containsExactly(second.toString(), first.toString());

        jdbc.update("UPDATE resolved_case SET updated_at='2026-01-03T00:00:00Z' WHERE id IN (?,?)", first, second);
        List<String> byId = new ArrayList<>(List.of(first.toString(), second.toString()));
        // PostgreSQL uuid 정렬은 소문자 16진 문자열의 사전순과 같다.
        byId.sort(java.util.Comparator.reverseOrder());
        assertThat(ids(result(cases(staff)))).containsExactlyElementsOf(byId);
    }

    @Test
    void 삭제된_사례는_목록에_나오지_않는다() throws Exception {
        UUID caseId = draft();
        jdbc.update("UPDATE resolved_case SET deleted_at=now() WHERE id=?", caseId);
        assertThat(ids(result(cases(staff)))).doesNotContain(caseId.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"state=PUBLISHED", "state=", "systemId=not-uuid", "page=-1", "size=0", "size=101"})
    void 잘못된_목록_조건은_기본값으로_보정하지_않고_400이다(String condition) throws Exception {
        String[] pair = condition.split("=", 2);
        mvc.perform(cases(staff).param(pair[0], pair[1]))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    // ---------- 상세 ----------

    @Test
    void 초안_상세는_원_처리기록과_저장형식을_그대로_투영하고_비공개다() throws Exception {
        UUID caseId = draft();
        JsonNode ticketDetail = detail();
        JsonNode confirmation = ticketDetail.path("activeResolutionRequest");
        JsonNode body = result(caseDetail(staff, caseId));

        assertThat(fieldNames(body)).containsExactlyInAnyOrder("version", "approval", "indexes", "publication");
        JsonNode version = body.path("version");
        assertThat(version.path("id").asString()).isEqualTo(caseId.toString());
        assertThat(version.path("caseVersion").asInt()).isEqualTo(1);
        assertThat(version.path("state").asString()).isEqualTo("DRAFT");
        assertThat(version.path("ticketId").asString()).isEqualTo(ticket.toString());
        assertThat(version.path("problemId").asString()).isEqualTo(problem.toString());
        assertThat(version.path("workRecordId").asString()).isEqualTo(ticketDetail.path("workRecords").get(0).path("id").asString());
        assertThat(version.path("resolutionRequestId").asString()).isEqualTo(confirmation.path("id").asString());
        assertThat(version.path("systemId").asString()).isEqualTo(system.toString());
        assertThat(version.path("caseType").asString()).isEqualTo("RESOLUTION");
        assertThat(version.path("problemStatement").asString()).isEqualTo("가상 문제");
        assertThat(version.path("confirmedCause").isNull()).isTrue();
        assertThat(version.path("title").asString()).isEqualTo("가상 사례");
        // 조치·검증은 모델 제안이 아니라 담당자의 원 처리 기록 값이다.
        assertThat(version.path("action").asString()).isEqualTo("실제 조치");
        assertThat(version.path("verification").asString()).isEqualTo("정상 동작 확인");
        assertThat(version.path("applicability").asString()).isEqualTo("가상 환경 전용");
        assertThat(version.path("limitations")).isEqualTo(mapper.valueToTree(LIMITATIONS));
        assertThat(version.path("appliesToSystemVersion").isNull()).isTrue();
        assertThat(version.path("accessScope").asString()).isEqualTo("SYSTEM_STAFF");
        assertThat(version.path("origin").asString()).isEqualTo("SYNTHETIC");
        assertThat(version.path("confirmationOrigin").asString()).isEqualTo(confirmation.path("confirmationOrigin").asString());
        assertThat(version.path("sourceMessageIds")).isEqualTo(mapper.valueToTree(List.of(message.toString())));
        assertThat(version.path("createdAt").asString()).isNotBlank();
        assertThat(fieldNames(version)).hasSize(22);

        assertThat(body.path("approval").isNull()).isTrue();
        assertThat(body.path("indexes").isArray()).isTrue();
        assertThat(body.path("indexes")).isEmpty();
        JsonNode publication = body.path("publication");
        assertThat(publication.path("caseId").asString()).isEqualTo(caseId.toString());
        assertThat(publication.path("caseVersion").asInt()).isEqualTo(1);
        assertThat(publication.path("state").asString()).isEqualTo("PRIVATE");
        assertThat(publication.path("canSearch").asBoolean()).isFalse();
    }

    @Test
    void 현재_버전을_지정하면_같은_상세를_반환한다() throws Exception {
        UUID caseId = draft();
        JsonNode body = result(caseDetail(staff, caseId).param("caseVersion", "1"));
        assertThat(body.path("version").path("caseVersion").asInt()).isEqualTo(1);
    }

    @Test
    void 과거_버전을_지정하면_404다() throws Exception {
        UUID caseId = draft();
        jdbc.update("UPDATE resolved_case SET version_no=2 WHERE id=?", caseId);
        mvc.perform(caseDetail(staff, caseId).param("caseVersion", "1"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(result(caseDetail(staff, caseId)).path("version").path("caseVersion").asInt()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", ""})
    void 잘못된_caseVersion은_400이다(String value) throws Exception {
        UUID caseId = draft();
        mvc.perform(caseDetail(staff, caseId).param("caseVersion", value))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void 현재_사례_시스템_담당자가_아니면_존재여부를_숨기고_404다() throws Exception {
        UUID caseId = draft();
        for (UUID user : List.of(other, requester, admin)) {
            mvc.perform(caseDetail(user, caseId))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
        mvc.perform(caseDetail(other, UUID.randomUUID()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void 주담당자가_바뀌면_새_담당자만_볼_수_있다() throws Exception {
        UUID caseId = draft();
        jdbc.update("UPDATE it_system SET primary_owner_member_id=? WHERE id=?", other, system);
        mvc.perform(caseDetail(staff, caseId)).andExpect(status().isNotFound());
        assertThat(result(caseDetail(other, caseId)).path("version").path("id").asString()).isEqualTo(caseId.toString());
        assertThat(ids(result(cases(other)))).contains(caseId.toString());
    }

    @Test
    void 삭제된_사례는_404다() throws Exception {
        UUID caseId = draft();
        jdbc.update("UPDATE resolved_case SET deleted_at=now() WHERE id=?", caseId);
        mvc.perform(caseDetail(staff, caseId)).andExpect(status().isNotFound());
    }

    // ---------- 준비 ----------

    /** 담당자 해결 기록을 저장하고 Curator가 재사용 가능한 초안을 저장할 때까지 기다린다. */
    private UUID draft() throws Exception {
        when(client.snapshot(any())).thenAnswer(invocation -> reusableSnapshot(invocation.getArgument(0, String.class)));
        result(command(staff, "/tickets/" + ticket + "/work-records", work("VERIFIED_RESOLUTION")));
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(detail().path("caseGeneration").path("state").asString()).isEqualTo("SUCCEEDED"));
        return UUID.fromString(detail().path("caseGeneration").path("caseId").asString());
    }

    /**
     * 정렬 확인용으로 같은 티켓에 두 번째 해결 기록·Curator 실행·사례를 복제한다.
     * 한 티켓은 해결 기록 저장 뒤 다시 해결 기록을 받지 않으므로 API 대신 저장 행을 그대로 복사한다.
     */
    private UUID copyDraft(UUID caseId) {
        UUID work = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        UUID copy = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ticket_work_log(id,ticket_id,author_member_id,log_type,action_detail,verification_result,confirmed_cause,applied_version)
                SELECT ?,ticket_id,author_member_id,log_type,action_detail,verification_result,confirmed_cause,applied_version
                FROM ticket_work_log WHERE id=(SELECT source_work_log_id FROM resolved_case WHERE id=?)""", work, caseId);
        jdbc.update("""
                INSERT INTO agent_run(id,inquiry_id,ticket_id,trigger_work_log_id,task,route,run_status,input_version,input_snapshot,
                    model_version,prompt_version,schema_version,result,validation_result,started_at,completed_at)
                SELECT ?,inquiry_id,ticket_id,?,task,route,run_status,input_version,input_snapshot,
                    model_version,prompt_version,schema_version,result,
                    jsonb_set(validation_result,'{resources,0,id}',to_jsonb(CAST(? AS text))),started_at,completed_at
                FROM agent_run WHERE id=(SELECT curator_run_id FROM resolved_case WHERE id=?)""", run, work, copy.toString(), caseId);
        jdbc.update("""
                INSERT INTO resolved_case(id,case_number,source_work_log_id,it_system_id,case_type,title,problem_statement,
                    confirmed_facts,confirmed_cause,action_detail,verification_result,applicability,applied_version,
                    access_level,origin_type,curator_run_id,relation_candidates)
                SELECT ?,?,?,it_system_id,case_type,title,problem_statement,confirmed_facts,confirmed_cause,action_detail,
                    verification_result,applicability,applied_version,access_level,origin_type,?,relation_candidates
                FROM resolved_case WHERE id=?""", copy, "C-" + copy.toString().substring(24), work, run, caseId);
        return copy;
    }

    private JsonNode reusableSnapshot(String runId) {
        var result = mapper.createObjectNode().put("contractVersion", "hops-system-1")
                .put("runId", runId).put("kind", "CASE_DRAFT")
                .put("inquiryId", inquiry.toString()).put("inquiryVersion", 1).put("status", "SUCCEEDED");
        result.putNull("answer"); result.putNull("guide"); result.putNull("error");
        for (var field : List.of("problemProposals", "questions", "ticketProposals", "evidence")) result.putArray(field);
        ObjectNode proposal = result.putObject("caseProposal");
        proposal.put("reusable", true).put("title", "가상 사례").put("action", "모델 제안 조치")
                .put("verification", "모델 제안 검증").put("problemStatement", "가상 문제")
                .put("applicability", "가상 환경 전용").put("accessScope", "SYSTEM_STAFF");
        proposal.putNull("appliesToSystemVersion");
        proposal.set("limitations", mapper.valueToTree(LIMITATIONS));
        proposal.putArray("sourceMessageIds").add(message.toString());
        proposal.putArray("entityLinks").addObject().put("relation", "APPLIES_TO").put("targetId", system.toString())
                .putArray("evidenceIds").add(message.toString());
        var snapshot = mapper.createObjectNode().put("status", "SUCCEEDED");
        snapshot.set("result", result);
        return snapshot;
    }

    private MockHttpServletRequestBuilder cases(UUID user) {
        return as(user, get("/api/v2/cases"));
    }

    private MockHttpServletRequestBuilder caseDetail(UUID user, UUID caseId) {
        return as(user, get("/api/v2/cases/" + caseId));
    }

    private static JsonNode only(JsonNode page, UUID caseId) {
        assertThat(page.path("items")).hasSize(1);
        JsonNode item = page.path("items").get(0);
        assertThat(item.path("id").asString()).isEqualTo(caseId.toString());
        return item;
    }

    private static List<String> ids(JsonNode page) {
        List<String> result = new ArrayList<>();
        for (JsonNode item : page.path("items")) result.add(item.path("id").asString());
        return result;
    }

    private static List<String> fieldNames(JsonNode node) {
        return new ArrayList<>(node.propertyNames());
    }
}
