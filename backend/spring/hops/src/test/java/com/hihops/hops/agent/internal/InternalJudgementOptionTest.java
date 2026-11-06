package com.hihops.hops.agent.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.hihops.hops.inquiry.repository.InquiryRepository;
import com.hihops.hops.ticket.dto.StandardRequestResponse.KnowledgeEvidenceResponse;
import com.hihops.hops.ticket.service.TicketKnowledgeReader;

import tools.jackson.databind.JsonNode;

/**
 * 내부 판단 경계(옵션, 서버 설정 hops.agent.run.internal-judgement.enabled=true)가 켜진 서버의 지식 검색·관계 조회.
 * 기본 프로필(꺼짐)의 동작은 KnowledgeSearchApiTest·RelationSearchApiTest가 확인한다.
 * 이 옵션은 SECURITY.md 개정과 보안 리뷰 전에는 운영에서 켜지 않는다. 여기서는 켜졌을 때의 경계만 확인한다:
 * 담당자 전용 자료를 내부 판단용으로 읽을 수 있어도 사용자에게 닿는 경로는 엄격한 공개 검증이 막는다.
 */
@SpringBootTest(properties = {"SPRING_SERVICE_TOKEN=" + InternalRunApiTestBase.TOKEN, "hops.agent.run.internal-judgement.enabled=true",
        "hops.agent.run.internal-judgement.review-reference=TEST-REVIEW"})
class InternalJudgementOptionTest extends InternalRunApiTestBase {
    @Autowired TicketKnowledgeReader reader;
    @Autowired InquiryRepository inquiries;

    String endpoint = "knowledge-searches";

    @Override
    String endpoint() {
        return endpoint;
    }

    Map<String, Object> knowledgeBody(String query) {
        return new LinkedHashMap<>(Map.of("inquiryVersion", inquiryVersion, "problemRef", "new:first", "query", query));
    }

    Map<String, Object> relationBody(String keyword) {
        return new LinkedHashMap<>(Map.of("inquiryVersion", inquiryVersion, "problemRef", "new:first", "keywords", List.of(keyword)));
    }

    String runbookTitle() {
        return jdbc.queryForObject("SELECT title FROM knowledge_document WHERE source_data ->> 'documentKey' = 'runbook-ea-document-not-listed'", String.class);
    }

    List<String> documentKeys(JsonNode response) {
        return response.path("results").valueStream().map(r -> r.path("documentKey").asString()).toList();
    }

    // ---- 지식 검색

    @Test
    void 옵션이_켜져도_retrievalScopes가_없는_실행은_기본_프로필로_읽는다() throws Exception {
        JsonNode response = search(knowledgeBody(runbookTitle())).andExpect(status().isOk())
                .andExpect(jsonPath("$.applied.internalJudgement").value(false)).andExpect(jsonPath("$.applied.accessScopes[0]").value("EMPLOYEE")).json();

        assertThat(documentKeys(response)).noneMatch(key -> key.startsWith("runbook-"));
        assertThat(response.path("applied").path("accessScopes").size()).isEqualTo(1);
    }

    @Test
    void 켜진_실행은_주담당자가_아닌_문의자에게도_담당자_자료를_내부_판단용으로_돌려준다() throws Exception {
        withRetrievalScopes("EMPLOYEE", "SYSTEM_STAFF");

        JsonNode response = search(knowledgeBody(runbookTitle())).andExpect(status().isOk())
                .andExpect(jsonPath("$.applied.internalJudgement").value(true)).andExpect(jsonPath("$.applied.accessScopes[1]").value("SYSTEM_STAFF")).json();

        assertThat(documentKeys(response)).contains("runbook-ea-document-not-listed");
        JsonNode runbook = response.path("results").valueStream().filter(r -> r.path("documentKey").asString().equals("runbook-ea-document-not-listed")).findFirst().orElseThrow();
        assertThat(runbook.path("evidence").path("accessScope").asString()).isEqualTo("SYSTEM_STAFF");
        // 종류를 직접 요청해도 거부하지 않는다(내부 판단 범위 안).
        var runbookOnly = knowledgeBody(runbookTitle());
        runbookOnly.put("kinds", List.of("RUNBOOK"));
        search(runbookOnly).andExpect(jsonPath("$.outcome").value("FOUND"));
    }

    @Test
    void 내부_판단_읽기도_시스템_범위와_승인_상태를_넘지_못한다() throws Exception {
        withRetrievalScopes("EMPLOYEE", "SYSTEM_STAFF");

        // 접근 시스템이 공통 로그인뿐이면 전자결재 런북은 읽을 수 없다.
        jdbc.update("UPDATE agent_run SET input_snapshot = jsonb_set(input_snapshot, '{accessContext,systemIds}', to_jsonb(ARRAY[?::text])) WHERE id = ?",
                loginSystem.toString(), run);
        assertThat(documentKeys(search(knowledgeBody(runbookTitle())).json())).doesNotContain("runbook-ea-document-not-listed");

        // 시스템 범위 안이어도 미승인·평가용 문서는 읽을 수 없다.
        jdbc.update("UPDATE agent_run SET input_snapshot = jsonb_set(input_snapshot, '{accessContext,systemIds}', to_jsonb(ARRAY[?::text])) WHERE id = ?",
                eaSystem.toString(), run);
        assertThat(documentKeys(search(knowledgeBody(runbookTitle())).json())).contains("runbook-ea-document-not-listed");
        for (String change : List.of("review_status = 'PENDING'", "data_split = 'EVAL'", "deleted_at = now()")) {
            jdbc.update("UPDATE knowledge_document SET " + change + " WHERE source_data ->> 'documentKey' = 'runbook-ea-document-not-listed'");
            assertThat(documentKeys(search(knowledgeBody(runbookTitle())).json())).as(change).doesNotContain("runbook-ea-document-not-listed");
            jdbc.update("UPDATE knowledge_document SET review_status = 'APPROVED', data_split = 'RAG', deleted_at = NULL WHERE source_data ->> 'documentKey' = 'runbook-ea-document-not-listed'");
        }
    }

    @Test
    void 읽기_범위가_사용자_범위를_줄이는_어긋난_입력은_거부한다() throws Exception {
        withRetrievalScopes("SYSTEM_STAFF");

        search(knowledgeBody(runbookTitle())).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        withRetrievalScopes("EMPLOYEE", "ADMIN");
        search(knowledgeBody(runbookTitle())).andExpect(status().isForbidden());
    }

    @Test
    void 요청_본문으로는_내부_판단을_켤_수_없다() throws Exception {
        for (String field : List.of("retrievalScopes", "internalJudgement", "accessScope")) {
            var request = knowledgeBody(runbookTitle());
            request.put(field, List.of("SYSTEM_STAFF"));
            search(request).andExpect(status().isBadRequest());
        }
        assertThat(documentKeys(search(knowledgeBody(runbookTitle())).json())).noneMatch(key -> key.startsWith("runbook-"));
    }

    @Test
    void 내부_판단으로_읽은_담당자_근거는_사용자에게_닿는_근거_검증을_통과하지_못한다() throws Exception {
        withRetrievalScopes("EMPLOYEE", "SYSTEM_STAFF");
        JsonNode response = search(knowledgeBody(runbookTitle())).andExpect(status().isOk()).json();
        JsonNode runbook = response.path("results").valueStream().filter(r -> r.path("documentKey").asString().equals("runbook-ea-document-not-listed")).findFirst().orElseThrow();
        KnowledgeEvidenceResponse evidence = mapper.treeToValue(runbook.path("evidence"), KnowledgeEvidenceResponse.class);
        var requester = inquiries.findById(inquiry).orElseThrow().getRequester();

        // 안내·티켓 근거로 저장되기 전의 검증(TicketKnowledgeReader)은 내부 판단 읽기를 모른다. 주담당자가 아닌 문의자에게는 공개하지 않는다.
        assertThat(reader.visible(mapper.writeValueAsString(List.of(evidence)), requester)).isEmpty();
        // 같은 문서의 직원 공개 가이드는 그대로 통과한다.
        JsonNode guideResponse = search(knowledgeBody("전자결재에서 지난 문서를 조회하는 방법")).andExpect(status().isOk()).json();
        JsonNode guide = guideResponse.path("results").valueStream().filter(r -> r.path("evidence").path("accessScope").asString().equals("EMPLOYEE")).findFirst().orElseThrow();
        KnowledgeEvidenceResponse employeeEvidence = mapper.treeToValue(guide.path("evidence"), KnowledgeEvidenceResponse.class);
        assertThat(reader.visible(mapper.writeValueAsString(List.of(employeeEvidence)), requester)).containsExactly(employeeEvidence);
    }

    // ---- 관계 조회

    @Test
    void 관계_조회는_내부_판단용으로_원인과_담당자_전용_항목을_주되_직원에게_나갈_질문은_그대로다() throws Exception {
        endpoint = "relation-searches";
        JsonNode defaultView = search(relationBody("문서함에 문서가 안 보여요")).andExpect(status().isOk()).json();
        JsonNode defaultSymptom = defaultView.path("symptoms").get(0);
        assertThat(defaultSymptom.has("causes")).isFalse();
        List<String> defaultQuestions = defaultSymptom.path("checkItems").valueStream()
                .flatMap(check -> check.path("questions").valueStream()).map(q -> q.path("catalogKey").asString()).sorted().toList();

        withRetrievalScopes("EMPLOYEE", "SYSTEM_STAFF");
        JsonNode internal = search(relationBody("문서함에 문서가 안 보여요")).andExpect(status().isOk())
                .andExpect(jsonPath("$.applied.internalJudgement").value(true)).json();
        JsonNode symptom = internal.path("symptoms").get(0);

        assertThat(symptom.path("causes").size()).isEqualTo(4);
        assertThat(symptom.path("cases").size()).isPositive();
        assertThat(symptom.path("basis").path("documentKey").asString()).isEqualTo("runbook-ea-document-not-listed");
        JsonNode staffOnly = symptom.path("checkItems").valueStream().filter(c -> c.path("catalogKey").asString().equals("check-ea-view-permission-record")).findFirst().orElseThrow();
        assertThat(staffOnly.path("employeeDisclosable").asBoolean()).isFalse();
        assertThat(staffOnly.path("questions").size()).isZero();

        // 직원에게 질문으로 나갈 수 있는 것은 직원 공개로 표시된 확인 항목과 승인된 EMPLOYEE 질문 문구뿐이며 기본 프로필과 같은 집합이다.
        List<String> askable = symptom.path("checkItems").valueStream().filter(c -> c.path("employeeDisclosable").asBoolean())
                .flatMap(check -> check.path("questions").valueStream()).map(q -> q.path("catalogKey").asString()).sorted().toList();
        assertThat(askable).isEqualTo(defaultQuestions);
        List<UUID> questionIds = symptom.path("checkItems").valueStream().flatMap(check -> check.path("questions").valueStream())
                .map(q -> UUID.fromString(q.path("id").asString())).toList();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM relation_question_template WHERE id IN (" + String.join(",", questionIds.stream().map(id -> "'" + id + "'").toList())
                + ") AND (access_level <> 'EMPLOYEE' OR NOT employee_disclosable OR review_status <> 'APPROVED')", Integer.class)).isZero();
    }
}
