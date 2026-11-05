package com.hihops.hops.agent.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.hihops.hops.inquiry.repository.InquiryRepository;
import com.hihops.hops.knowledge.domain.AccessLevel;
import com.hihops.hops.knowledge.domain.CaseVisibility;
import com.hihops.hops.knowledge.repository.KnowledgeDocumentVersionRepository;
import com.hihops.hops.knowledge.service.KnowledgeEvidenceAssembler;
import com.hihops.hops.knowledge.service.KnowledgeVisibilityPolicy;
import com.hihops.hops.relation.repository.RelationSearchRepository;
import com.hihops.hops.relation.service.RelationSearchService;

import tools.jackson.databind.JsonNode;

/**
 * 관계 조회 내부 API(contracts/agents/KNOWLEDGE_TOOLS.md "관계 조회")를 실제 PostgreSQL과 MockMvc로 확인한다.
 * 시드(조직·직원·시스템·지식 문서·사례·관계)를 거래 안에서 적재하고 끝나면 되돌린다. 실제 AI 호출·질문 생성은 검증하지 않는다.
 */
class RelationSearchApiTest extends InternalRunApiTestBase {
    private static final String EA_DOC_SYMPTOM = "sym-ea-doc-not-listed";
    private static final String EA_ATTACH_SYMPTOM = "sym-ea-attach-not-open";
    private static final String STAFF_ONLY_CHECK = "check-ea-view-permission-record";

    @Autowired RelationSearchRepository repository;
    @Autowired KnowledgeDocumentVersionRepository versions;
    @Autowired KnowledgeEvidenceAssembler assembler;
    @Autowired InquiryRepository inquiries;

    @Override
    String endpoint() {
        return "relation-searches";
    }

    Map<String, Object> body(String... keywords) {
        var request = new LinkedHashMap<String, Object>(Map.of("inquiryVersion", inquiryVersion, "problemRef", "new:first"));
        if (keywords.length > 0) {
            request.put("keywords", List.of(keywords));
        }
        return request;
    }

    JsonNode symptom(JsonNode response, String catalogKey) {
        return response.path("symptoms").valueStream().filter(s -> s.path("catalogKey").asString().equals(catalogKey)).findFirst().orElseThrow();
    }

    List<String> keys(JsonNode items) {
        return items.valueStream().map(item -> item.path("catalogKey").asString()).toList();
    }

    // ---- 직원 실행: 질문 문구만 나간다

    @Test
    void 직원_실행은_증상과_직원_공개_확인_항목의_질문_문구만_받는다() throws Exception {
        JsonNode response = search(body("문서함에 문서가 안 보여요")).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("FOUND"))
                .andExpect(jsonPath("$.applied.accessScopes[0]").value("EMPLOYEE")).andReturn().json();
        JsonNode symptom = symptom(response, EA_DOC_SYMPTOM);
        assertThat(symptom.path("label").asString()).isEqualTo("문서가 문서함에 보이지 않음");
        assertThat(symptom.path("accessScope").asString()).isEqualTo("EMPLOYEE");
        // 원인은 확인 항목을 찾는 데만 쓰고 직원 응답에는 키조차 없다.
        assertThat(symptom.has("causes")).isFalse();
        assertThat(symptom.path("cases").size()).isZero();

        Set<String> disclosable = Set.copyOf(jdbc.queryForList("SELECT catalog_key FROM relation_check_item WHERE employee_disclosable AND review_status = 'APPROVED'", String.class));
        List<String> checkKeys = keys(symptom.path("checkItems"));
        assertThat(checkKeys).containsExactlyInAnyOrder("check-ea-draft-date", "check-ea-search-period", "check-ea-participation",
                "check-ea-submit-state", "check-ea-filter-condition");
        assertThat(disclosable).containsAll(checkKeys);
        for (JsonNode check : symptom.path("checkItems")) {
            assertThat(check.path("employeeDisclosable").asBoolean()).isTrue();
            assertThat(check.has("label")).isFalse();
            assertThat(check.path("questions").size()).isPositive();
            for (JsonNode question : check.path("questions")) {
                assertThat(question.path("text").asString()).isNotBlank();
            }
        }

        // 응답 어디에도 담당자 전용 확인 항목·원인의 키와 이름, 담당자 런북의 키가 없다.
        String text = mapper.writeValueAsString(response);
        assertThat(text).doesNotContain(STAFF_ONLY_CHECK).doesNotContain("cause-").doesNotContain("runbook-");
        for (String label : jdbc.queryForList("SELECT label FROM relation_cause", String.class)) {
            assertThat(text).as(label).doesNotContain(label);
        }
        for (String label : jdbc.queryForList("SELECT label FROM relation_check_item", String.class)) {
            assertThat(text).as(label).doesNotContain(label);
        }
        // 근거는 담당자 런북이라 이 실행에는 공개하지 않는다. 그래도 근거가 있다는 사실은 visible=false로 남는다.
        assertThat(symptom.path("basis").path("visible").asBoolean()).isFalse();
        assertThat(symptom.path("basis").has("documentKey")).isFalse();
        assertThat(symptom.path("checkItems").get(0).path("basis").path("visible").asBoolean()).isFalse();
    }

    @Test
    void 직원_전용_조회는_저장소에서부터_직원_공개_항목과_EMPLOYEE_질문만_고른다() {
        List<UUID> symptoms = jdbc.queryForList("SELECT id FROM relation_symptom", UUID.class);
        var employeeChecks = repository.findCheckItems(symptoms, false, List.of("EMPLOYEE"), new RelationSearchRepository.Viewer(UUID.fromString(REQUESTER1), false));
        assertThat(employeeChecks).isNotEmpty().allMatch(RelationSearchRepository.CheckRow::employeeDisclosable);
        assertThat(employeeChecks).extracting(RelationSearchRepository.CheckRow::catalogKey).doesNotContain(STAFF_ONLY_CHECK);
        // 담당자 범위(주담당자가 아닌 문의자)도 직원 공개 항목만 얻고, 공개 범위가 STAFF인 담당자 전용 항목은 얻지 못한다.
        assertThat(repository.findCheckItems(symptoms, true, List.of("EMPLOYEE", "STAFF"), new RelationSearchRepository.Viewer(UUID.fromString(REQUESTER1), false)))
                .extracting(RelationSearchRepository.CheckRow::catalogKey).doesNotContain(STAFF_ONLY_CHECK);

        List<UUID> checkIds = jdbc.queryForList("SELECT id FROM relation_check_item", UUID.class);
        var questions = repository.findQuestions(checkIds, false, List.of("EMPLOYEE"), new RelationSearchRepository.Viewer(UUID.fromString(REQUESTER1), false));
        assertThat(questions).isNotEmpty().allMatch(question -> "EMPLOYEE".equals(question.accessLevel()));
        assertThat(questions).hasSize(20);
    }

    @Test
    void 확인_항목은_후보_원인을_더_많이_가르는_순서다() throws Exception {
        JsonNode response = search(body("첨부 파일이 열리지 않아요")).andExpect(status().isOk()).andReturn().json();
        // check-ea-error-message는 두 후보 원인(저장소 오류·문서 열람 권한)을 가르고 나머지는 하나다.
        assertThat(keys(symptom(response, EA_ATTACH_SYMPTOM).path("checkItems")).get(0)).isEqualTo("check-ea-error-message");
    }

    @Test
    void 공개_근거가_있는_정책에서_나온_증상은_근거_문서를_보인다() throws Exception {
        JsonNode response = search(body("권한 신청이 오래 승인되지 않아요")).andExpect(status().isOk()).andReturn().json();
        JsonNode basis = symptom(response, "sym-login-permission-pending").path("basis");
        assertThat(basis.path("visible").asBoolean()).isTrue();
        assertThat(basis.path("documentKey").asString()).isEqualTo("policy-login-permission-approval");
        assertThat(basis.path("documentVersion").asInt()).isEqualTo(1);
    }

    @Test
    void 직원_공개_표시가_없거나_미승인이면_질문과_확인_항목이_빠진다() throws Exception {
        String key = "check-ea-search-period";
        assertThat(keys(symptom(search(body("문서함에 문서가 안 보여요")).json(), EA_DOC_SYMPTOM).path("checkItems"))).contains(key);

        // 확인 항목의 직원 공개 표시를 끄면 질문 문구가 승인·공개여도 나가지 않는다.
        jdbc.update("UPDATE relation_check_item SET employee_disclosable = false WHERE catalog_key = ?", key);
        assertThat(keys(symptom(search(body("문서함에 문서가 안 보여요")).json(), EA_DOC_SYMPTOM).path("checkItems"))).doesNotContain(key);
        jdbc.update("UPDATE relation_check_item SET employee_disclosable = true WHERE catalog_key = ?", key);

        // 확인 항목이 미승인이면 빠진다.
        jdbc.update("UPDATE relation_check_item SET review_status = 'PENDING' WHERE catalog_key = ?", key);
        assertThat(keys(symptom(search(body("문서함에 문서가 안 보여요")).json(), EA_DOC_SYMPTOM).path("checkItems"))).doesNotContain(key);
        jdbc.update("UPDATE relation_check_item SET review_status = 'APPROVED' WHERE catalog_key = ?", key);

        // 질문 문구가 직원 공개가 아니거나 미승인이면 질문이 없는 확인 항목은 직원 응답에 나타나지 않는다.
        jdbc.update("UPDATE relation_question_template SET employee_disclosable = false, access_level = 'STAFF' WHERE check_item_id = "
                + "(SELECT id FROM relation_check_item WHERE catalog_key = ?)", key);
        assertThat(keys(symptom(search(body("문서함에 문서가 안 보여요")).json(), EA_DOC_SYMPTOM).path("checkItems"))).doesNotContain(key);
        jdbc.update("UPDATE relation_question_template SET employee_disclosable = true, access_level = 'EMPLOYEE' WHERE check_item_id = "
                + "(SELECT id FROM relation_check_item WHERE catalog_key = ?)", key);
        jdbc.update("UPDATE relation_question_template SET review_status = 'PENDING' WHERE check_item_id = "
                + "(SELECT id FROM relation_check_item WHERE catalog_key = ?)", key);
        assertThat(keys(symptom(search(body("문서함에 문서가 안 보여요")).json(), EA_DOC_SYMPTOM).path("checkItems"))).doesNotContain(key);
        jdbc.update("UPDATE relation_question_template SET review_status = 'APPROVED' WHERE check_item_id = "
                + "(SELECT id FROM relation_check_item WHERE catalog_key = ?)", key);
        assertThat(keys(symptom(search(body("문서함에 문서가 안 보여요")).json(), EA_DOC_SYMPTOM).path("checkItems"))).contains(key);
    }

    @Test
    void 미승인_삭제_증상과_연결은_후보에도_쓰지_않는다() throws Exception {
        jdbc.update("UPDATE relation_symptom SET review_status = 'PENDING' WHERE catalog_key = ?", EA_DOC_SYMPTOM);
        search(body("문서함에 문서가 안 보여요")).andExpect(jsonPath("$.outcome").value("NO_RESULT"));
        jdbc.update("UPDATE relation_symptom SET review_status = 'APPROVED' WHERE catalog_key = ?", EA_DOC_SYMPTOM);

        // 증상→원인 연결이 미승인이면 그 원인의 확인 항목은 찾지 못한다(원인 4개 중 상신 여부 원인만 끊는다).
        jdbc.update("UPDATE relation_symptom_cause SET deleted_at = now() WHERE cause_id = (SELECT id FROM relation_cause WHERE catalog_key = 'cause-ea-draft-not-submitted')");
        assertThat(keys(symptom(search(body("문서함에 문서가 안 보여요")).json(), EA_DOC_SYMPTOM).path("checkItems"))).doesNotContain("check-ea-submit-state");
    }

    @Test
    void 내부_판단_경계가_꺼진_서버는_retrievalScopes가_있어도_직원_조회로만_답한다() throws Exception {
        withRetrievalScopes("EMPLOYEE", "SYSTEM_STAFF");

        JsonNode response = search(body("문서함에 문서가 안 보여요")).andExpect(status().isOk())
                .andExpect(jsonPath("$.applied.internalJudgement").value(false)).andReturn().json();
        JsonNode symptom = symptom(response, EA_DOC_SYMPTOM);
        assertThat(response.path("applied").path("accessScopes").size()).isEqualTo(1);
        assertThat(symptom.has("causes")).isFalse();
        assertThat(keys(symptom.path("checkItems"))).doesNotContain(STAFF_ONLY_CHECK);
        assertThat(mapper.writeValueAsString(response)).doesNotContain("cause-").doesNotContain("runbook-");
    }

    // ---- 결과·응답

    @Test
    void 일치하는_증상이_없으면_NO_RESULT이고_오류와_구분된다() throws Exception {
        search(body("zzzzqqqq")).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("NO_RESULT"))
                .andExpect(jsonPath("$.deniedReason").doesNotExist()).andExpect(jsonPath("$.symptoms").isEmpty());
    }

    @Test
    void 조회_실패는_결과_없음이_아니라_503이다() throws Exception {
        jdbc.execute("ALTER TABLE relation_symptom RENAME TO relation_symptom_unavailable");
        search(body("문서함에 문서가 안 보여요")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SEARCH_UNAVAILABLE"));
    }

    @Test
    void 기능_후보만으로도_같은_기능의_증상을_찾고_기능이_같으면_가산한다() throws Exception {
        UUID documentView = jdbc.queryForObject("SELECT id FROM system_feature WHERE code = 'DOCUMENT_VIEW' AND it_system_id = ?", UUID.class, eaSystem);
        var byFeature = body();
        byFeature.put("featureIds", List.of(documentView));
        JsonNode response = search(byFeature).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("FOUND")).andReturn().json();
        assertThat(keys(response.path("symptoms"))).containsExactly(EA_DOC_SYMPTOM);
        assertThat(response.path("symptoms").get(0).path("featureCode").asString()).isEqualTo("DOCUMENT_VIEW");

        var both = body("문서함에 문서가 안 보여요");
        both.put("featureIds", List.of(documentView));
        double withFeature = search(both).json().path("symptoms").get(0).path("score").asDouble();
        double keywordOnly = search(body("문서함에 문서가 안 보여요")).json().path("symptoms").get(0).path("score").asDouble();
        assertThat(withFeature).isGreaterThan(keywordOnly).isLessThanOrEqualTo(1.0);
    }

    @Test
    void limit은_1에서_5까지이고_증상을_자른다() throws Exception {
        for (int limit : List.of(0, 6, -1)) {
            var request = body("파일 문서 로그인 열리지 않음");
            request.put("limit", limit);
            search(request).andExpect(status().isBadRequest());
        }
        JsonNode all = search(body("열리지 않음 로그인 문서")).andExpect(status().isOk()).andReturn().json();
        assertThat(all.path("symptoms").size()).isBetween(2, 5);
        var one = body("열리지 않음 로그인 문서");
        one.put("limit", 1);
        JsonNode top = search(one).andExpect(status().isOk()).andReturn().json();
        assertThat(top.path("symptoms").size()).isEqualTo(1);
        assertThat(top.path("symptoms").get(0).path("catalogKey").asString()).isEqualTo(all.path("symptoms").get(0).path("catalogKey").asString());
        // 같은 질의는 같은 순서다.
        assertThat(keys(search(body("열리지 않음 로그인 문서")).json().path("symptoms"))).isEqualTo(keys(all.path("symptoms")));
    }

    // ---- 범위

    @Test
    void 실행의_접근_시스템과_기능_밖은_거부하고_안에서는_좁힐_수_있다() throws Exception {
        var outside = body("문서함에 문서가 안 보여요");
        outside.put("systemIds", List.of(UUID.randomUUID()));
        search(outside).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("DENIED")).andExpect(jsonPath("$.deniedReason").value("SYSTEM_NOT_ALLOWED"));

        // 전자결재로 좁히면 공통 로그인 증상은 키워드가 맞아도 나오지 않는다.
        var narrowed = body("로그인했는데 다시 로그인 화면으로 돌아와요");
        narrowed.put("systemIds", List.of(eaSystem));
        search(narrowed).andExpect(jsonPath("$.outcome").value("NO_RESULT"));
        search(body("로그인했는데 다시 로그인 화면으로 돌아와요")).andExpect(jsonPath("$.outcome").value("FOUND"));

        // 없는 기능, 좁힌 시스템 밖의 기능은 FEATURE_NOT_ALLOWED다.
        UUID loginFeature = jdbc.queryForObject("SELECT id FROM system_feature WHERE it_system_id = ? ORDER BY code LIMIT 1", UUID.class, loginSystem);
        for (UUID feature : List.of(UUID.randomUUID(), loginFeature)) {
            var request = body("문서함에 문서가 안 보여요");
            request.put("featureIds", List.of(feature));
            request.put("systemIds", List.of(eaSystem));
            search(request).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("DENIED"))
                    .andExpect(jsonPath("$.deniedReason").value("FEATURE_NOT_ALLOWED"));
        }

        // 실행 입력의 접근 시스템이 전자결재뿐이면 공통 로그인 기능은 요청해도 거부된다.
        jdbc.update("UPDATE agent_run SET input_snapshot = jsonb_set(input_snapshot, '{accessContext,systemIds}', to_jsonb(ARRAY[?::text])) WHERE id = ?",
                eaSystem.toString(), run);
        var loginFeatureRequest = body();
        loginFeatureRequest.put("featureIds", List.of(loginFeature));
        search(loginFeatureRequest).andExpect(jsonPath("$.outcome").value("DENIED")).andExpect(jsonPath("$.deniedReason").value("FEATURE_NOT_ALLOWED"));
        search(body("로그인했는데 다시 로그인 화면으로 돌아와요")).andExpect(jsonPath("$.outcome").value("NO_RESULT"));
    }

    @Test
    void 요청_본문으로_접근_범위를_넓히려는_시도와_잘못된_형식은_400이다() throws Exception {
        for (String field : List.of("accessScope", "purpose", "allowedDocumentScopes", "retrievalScopes", "memberId", "kinds")) {
            var request = body("문서함에 문서가 안 보여요");
            request.put(field, "SYSTEM_STAFF");
            search(request).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        // 검색할 단어도 기능 후보도 없으면 무엇을 찾을지 알 수 없다.
        search(body()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        search(body("어떻게 하나요")).andExpect(status().isBadRequest());
        var emptyKeywords = body();
        emptyKeywords.put("keywords", List.of());
        search(emptyKeywords).andExpect(status().isBadRequest());
        search(body("   ")).andExpect(status().isBadRequest());
        var unknownProblem = body("문서함에 문서가 안 보여요");
        unknownProblem.put("problemRef", UUID.randomUUID().toString());
        search(unknownProblem).andExpect(status().isBadRequest());
    }

    // ---- 인증·실행 검증

    @Test
    void 내부_API는_JWT나_잘못된_서비스_토큰을_받지_않는다() throws Exception {
        for (String token : List.of("", "Bearer wrong")) {
            mvc.perform(post(path()).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(body("문서")))).andExpect(status().isUnauthorized());
        }
        mvc.perform(post(path()).with(requester()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body("문서")))).andExpect(status().isUnauthorized());
    }

    @Test
    void 없는_실행_이전_버전_기한초과_권한변경을_거부한다() throws Exception {
        mvc.perform(post("/internal/v2/runs/" + UUID.randomUUID() + "/relation-searches").header("Authorization", "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body("문서")))).andExpect(status().isNotFound());

        var stale = body("문서함에 문서가 안 보여요");
        stale.put("inquiryVersion", inquiryVersion + 1);
        search(stale).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INQUIRY_VERSION_CONFLICT"));

        jdbc.update("UPDATE agent_run SET input_snapshot = jsonb_set(input_snapshot, '{expiresAt}', '\"2020-01-01T00:00:00Z\"') WHERE id = ?", run);
        search(body("문서함에 문서가 안 보여요")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));
        jdbc.update("UPDATE agent_run SET input_snapshot = jsonb_set(input_snapshot, '{expiresAt}', to_jsonb(?::text)) WHERE id = ?",
                Instant.now().plusSeconds(300).toString(), run);
        jdbc.update("UPDATE member SET policy_revision = policy_revision + 1 WHERE id = ?::uuid", REQUESTER1);
        search(body("문서함에 문서가 안 보여요")).andExpect(status().isForbidden());
    }

    // ---- 담당자 범위와 사례 이웃

    @Test
    void 담당자_범위_실행도_주담당자가_아니면_원인과_담당자_전용_항목을_받지_못한다() throws Exception {
        allowStaffScope();

        JsonNode symptom = symptom(search(body("문서함에 문서가 안 보여요")).andExpect(status().isOk()).json(), EA_DOC_SYMPTOM);
        assertThat(symptom.path("causes").size()).isZero();
        assertThat(symptom.path("cases").size()).isZero();
        assertThat(keys(symptom.path("checkItems"))).doesNotContain(STAFF_ONLY_CHECK);
        for (JsonNode check : symptom.path("checkItems")) {
            assertThat(check.path("employeeDisclosable").asBoolean()).isTrue();
        }
    }

    @Test
    void 담당자_범위_실행은_주담당자에게만_원인_전체_확인_항목_사례를_보인다() throws Exception {
        allowStaffScope();
        jdbc.update("UPDATE it_system SET primary_owner_member_id = ?::uuid WHERE id = ?", REQUESTER1, eaSystem);

        JsonNode response = search(body("문서함에 문서가 안 보여요")).andExpect(status().isOk()).andExpect(jsonPath("$.applied.accessScopes[1]").value("SYSTEM_STAFF")).andReturn().json();
        JsonNode symptom = symptom(response, EA_DOC_SYMPTOM);
        assertThat(keys(symptom.path("causes"))).containsExactlyInAnyOrder("cause-ea-period-out-of-range", "cause-ea-no-view-permission",
                "cause-ea-draft-not-submitted", "cause-ea-filter-narrow");
        JsonNode noPermission = symptom.path("causes").valueStream().filter(c -> c.path("catalogKey").asString().equals("cause-ea-no-view-permission")).findFirst().orElseThrow();
        assertThat(noPermission.path("accessScope").asString()).isEqualTo("SYSTEM_STAFF");
        assertThat(noPermission.path("label").asString()).isNotBlank();
        assertThat(noPermission.path("checkItemKeys").valueStream().map(JsonNode::asString).toList())
                .containsExactly("check-ea-participation", STAFF_ONLY_CHECK);
        // 근거 런북이 이 실행에 공개 가능하므로 문서 키가 보인다.
        assertThat(symptom.path("basis").path("documentKey").asString()).isEqualTo("runbook-ea-document-not-listed");

        JsonNode staffOnly = symptom.path("checkItems").valueStream().filter(c -> c.path("catalogKey").asString().equals(STAFF_ONLY_CHECK)).findFirst().orElseThrow();
        assertThat(staffOnly.path("employeeDisclosable").asBoolean()).isFalse();
        assertThat(staffOnly.path("label").asString()).isNotBlank();
        assertThat(staffOnly.path("questions").size()).isZero();

        // 승인 사례 이웃: 색인 상태는 PENDING 그대로, 사례 번호가 문서 키 자리에 온다.
        JsonNode neighbor = symptom.path("cases").get(0);
        assertThat(neighbor.path("documentKey").asString()).startsWith("SEED-C-");
        assertThat(neighbor.path("evidence").path("kind").asString()).isEqualTo("CASE");
        assertThat(neighbor.path("caseIndexStatus").path("vector").asString()).isEqualTo("PENDING");
        assertThat(neighbor.path("caseIndexStatus").path("graph").asString()).isEqualTo("PENDING");
        // 다른 시스템의 증상에는 이 문의자가 담당자가 아니라 사례가 없다.
        JsonNode other = symptom(search(body("로그인했는데 다시 로그인 화면으로 돌아와요")).json(), "sym-login-loop");
        assertThat(other.path("causes").size()).isZero();
        assertThat(other.path("cases").size()).isZero();
    }

    @Test
    void 사례_이웃은_공개_규칙_설정을_따른다() throws Exception {
        jdbc.update("UPDATE it_system SET primary_owner_member_id = ?::uuid WHERE id = ?", REQUESTER1, eaSystem);
        UUID member = inquiries.findById(inquiry).orElseThrow().getRequester().getId();
        var query = new RelationSearchService.Query(List.of("문서함"), Set.of(), Set.of(AccessLevel.EMPLOYEE, AccessLevel.STAFF), true,
                Set.of(eaSystem), member, false, 5);
        var basic = new RelationSearchService(repository, versions, assembler, new KnowledgeVisibilityPolicy(CaseVisibility.APPROVED_SNAPSHOT));
        var indexed = new RelationSearchService(repository, versions, assembler, new KnowledgeVisibilityPolicy(CaseVisibility.INDEXED));

        assertThat(basic.search(query).get(0).cases()).isNotEmpty();
        assertThat(indexed.search(query).get(0).cases()).isEmpty();
        jdbc.update("UPDATE resolved_case SET vector_status = 'SUCCEEDED', graph_status = 'SUCCEEDED' WHERE it_system_id = ?", eaSystem);
        assertThat(indexed.search(query).get(0).cases()).isNotEmpty();
    }

    @Test
    void 사례_승인_스냅샷이_바뀌면_사례_이웃을_조용히_빼지_않고_503이다() throws Exception {
        allowStaffScope();
        jdbc.update("UPDATE it_system SET primary_owner_member_id = ?::uuid WHERE id = ?", REQUESTER1, eaSystem);
        jdbc.update("UPDATE knowledge_document_version SET content_digest = repeat('1', 64) WHERE knowledge_document_id IN "
                + "(SELECT id FROM knowledge_document WHERE document_type = 'CASE' AND it_system_id = ?)", eaSystem);

        search(body("문서함에 문서가 안 보여요")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SEARCH_UNAVAILABLE"));
    }
}
