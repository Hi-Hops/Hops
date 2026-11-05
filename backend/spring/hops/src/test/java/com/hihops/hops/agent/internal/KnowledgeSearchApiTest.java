package com.hihops.hops.agent.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.hihops.hops.inquiry.domain.Inquiry;
import com.hihops.hops.inquiry.repository.InquiryRepository;
import com.hihops.hops.knowledge.domain.CaseVisibility;
import com.hihops.hops.knowledge.repository.KnowledgeDocumentVersionRepository;
import com.hihops.hops.knowledge.repository.KnowledgeSearchRepository;
import com.hihops.hops.knowledge.service.KnowledgeEvidenceAssembler;
import com.hihops.hops.knowledge.service.KnowledgeSearchService;
import com.hihops.hops.knowledge.service.KnowledgeVisibilityPolicy;
import com.hihops.hops.knowledge.domain.AccessLevel;
import com.hihops.hops.knowledge.domain.DocumentType;
import com.hihops.hops.ticket.dto.StandardRequestResponse.KnowledgeEvidenceResponse;
import com.hihops.hops.ticket.service.TicketKnowledgeReader;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 지식 검색 내부 API(contracts/agents/KNOWLEDGE_TOOLS.md)를 실제 PostgreSQL과 MockMvc로 확인한다.
 * 시드(조직·직원·시스템·지식 문서 16건·사례 6건)를 거래 안에서 적재하고 끝나면 되돌린다. 실제 AI 호출·모델 품질은 검증하지 않는다.
 */
@SpringBootTest(properties = "SPRING_SERVICE_TOKEN=" + KnowledgeSearchApiTest.TOKEN)
@AutoConfigureMockMvc
@ActiveProfiles({"db", "test"})
@Transactional
class KnowledgeSearchApiTest {
    static final String TOKEN = "dGVzdC10b2tlbi1mb3ItdGlja2V0LWNyZWF0aW9uLWFwaQ";
    private static final String REQUESTER1 = "a0000000-0000-7000-8000-000000000101";
    private static final String EA_QUERY = "전자결재에서 지난 문서를 조회하는 방법";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper mapper;
    @Autowired InquiryRepository inquiries;
    @Autowired TicketKnowledgeReader reader;
    @Autowired KnowledgeSearchRepository searchRepository;
    @Autowired KnowledgeDocumentVersionRepository versions;
    @Autowired KnowledgeEvidenceAssembler assembler;
    @Autowired com.hihops.hops.itsystem.repository.ItSystemQueryRepository systems;
    @Autowired com.hihops.hops.ticket.service.TicketStoredData storedData;

    UUID inquiry;
    UUID run;
    UUID eaSystem;
    UUID loginSystem;
    int inquiryVersion;

    @BeforeEach
    void seedAndStartRun() throws Exception {
        // R__ 파일은 이름 순으로 실행된다. 본문이 $kb$ 인용 안에 세미콜론을 포함하므로 문장을 나누지 않고 서버에 통째로 보낸다.
        for (String file : List.of("R__seed_dev_department_member.sql", "R__seed_dev_it_system.sql",
                "R__seed_dev_knowledge.sql", "R__seed_dev_knowledge_cases.sql")) {
            jdbc.execute(new ClassPathResource("db/seed/" + file).getContentAsString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
        }
        eaSystem = system("ELECTRONIC_APPROVAL");
        loginSystem = system("COMMON_LOGIN");
        startRun();
    }

    /**
     * 시드 문의(SEED-Q-001)의 상담 실행을 접수된 현재 실행으로 되돌려 둔다. 실행 입력은 AnalysisInputFactory가 만드는 것과 같은 키를 갖는다.
     * 문의 접수 API는 거래 밖에서만 호출할 수 있어 이 테스트(거래 안에서 시드를 되돌림)에서는 직접 만든다.
     */
    private void startRun() throws Exception {
        inquiry = jdbc.queryForObject("SELECT id FROM inquiry WHERE inquiry_number = 'SEED-Q-001'", UUID.class);
        run = jdbc.queryForObject("SELECT id FROM agent_run WHERE inquiry_id = ? AND task = 'INQUIRY_ANALYSIS' ORDER BY created_at DESC LIMIT 1",
                UUID.class, inquiry);
        int version = jdbc.queryForObject("SELECT version FROM inquiry WHERE id = ?", Integer.class, inquiry);
        int policyRevision = jdbc.queryForObject("SELECT policy_revision FROM member WHERE id = ?::uuid", Integer.class, REQUESTER1);
        List<String> systemIds = jdbc.queryForList("SELECT id::text FROM it_system WHERE deleted_at IS NULL ORDER BY code", String.class);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("contractVersion", "hops-system-1");
        snapshot.put("runId", run);
        snapshot.put("kind", "INQUIRY_ANALYSIS");
        snapshot.put("inquiryId", inquiry);
        snapshot.put("inquiryVersion", version);
        snapshot.put("answerMessageId", UUID.randomUUID());
        snapshot.put("problemIds", List.of());
        snapshot.put("messages", List.of());
        snapshot.put("problems", List.of());
        snapshot.put("systems", List.of());
        snapshot.put("accessContext", Map.of("actorId", REQUESTER1, "purpose", "EMPLOYEE_HELP", "systemIds", systemIds,
                "allowedDocumentScopes", List.of("EMPLOYEE"), "policyRevision", policyRevision));
        snapshot.put("existingWorkItems", List.of());
        snapshot.put("workRecord", null);
        snapshot.put("expiresAt", Instant.now().plusSeconds(300).toString());
        snapshot.put("limits", Map.of("maxModelCalls", 8, "maxInputTokens", 60000, "maxOutputTokens", 12000, "maxToolCalls", 10, "maxRetries", 2));
        jdbc.update("UPDATE agent_run SET run_status = 'RUNNING', input_version = ?, input_snapshot = ?::jsonb, completed_at = NULL WHERE id = ?",
                version, mapper.writeValueAsString(snapshot), run);
        jdbc.update("UPDATE inquiry SET current_run_id = ? WHERE id = ?", run, inquiry);
        inquiryVersion = version;
    }

    // ---- 접근 범위

    @Test
    void 직원_실행은_가이드와_정책만_찾고_담당자_런북과_사례는_결과에_없다() throws Exception {
        String runbookTitle = jdbc.queryForObject("SELECT title FROM knowledge_document WHERE source_data ->> 'documentKey' = 'runbook-ea-document-not-listed'", String.class);

        JsonNode found = search(body(EA_QUERY)).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("FOUND"))
                .andExpect(jsonPath("$.applied.accessScopes[0]").value("EMPLOYEE")).andReturn().json();
        assertThat(documentKeys(found)).contains("guide-ea-document-search");
        for (JsonNode result : found.path("results")) {
            assertThat(result.path("evidence").path("kind").asString()).isIn("GUIDE", "POLICY");
            assertThat(result.path("evidence").path("accessScope").asString()).isEqualTo("EMPLOYEE");
        }

        // 담당자 런북의 제목으로 찾아도 직원 실행에는 나오지 않는다.
        JsonNode byRunbookTitle = search(body(runbookTitle)).andExpect(status().isOk()).andReturn().json();
        assertThat(documentKeys(byRunbookTitle)).noneMatch(key -> key.startsWith("runbook-") || key.startsWith("SEED-C-"));
    }

    @Test
    void 요청_본문으로_접근_범위를_넓히려는_시도는_거부한다() throws Exception {
        for (String field : List.of("accessScope", "purpose", "allowedDocumentScopes", "memberId", "retrievalScopes")) {
            var request = body(EA_QUERY);
            request.put(field, "SYSTEM_STAFF");
            search(request).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
    }

    @Test
    void 직원_실행이_런북이나_사례를_직접_요청하면_조용히_빼지_않고_거부한다() throws Exception {
        for (String kind : List.of("RUNBOOK", "CASE")) {
            var request = body(EA_QUERY);
            request.put("kinds", List.of("GUIDE", kind));
            search(request).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("DENIED"))
                    .andExpect(jsonPath("$.deniedReason").value("KIND_NOT_ALLOWED")).andExpect(jsonPath("$.results").isEmpty());
        }
    }

    @Test
    void 실행의_접근_시스템_밖은_거부하고_안에서는_좁힐_수_있다() throws Exception {
        var outside = body(EA_QUERY);
        outside.put("systemIds", List.of(UUID.randomUUID()));
        search(outside).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("DENIED"))
                .andExpect(jsonPath("$.deniedReason").value("SYSTEM_NOT_ALLOWED"));

        var narrowed = body("로그인 권한 신청 방법");
        narrowed.put("systemIds", List.of(loginSystem));
        JsonNode result = search(narrowed).andExpect(status().isOk()).andReturn().json();
        assertThat(result.path("results").size()).isPositive();
        for (JsonNode item : result.path("results")) {
            JsonNode systemId = item.path("evidence").path("systemId");
            assertThat(systemId.isNull() || systemId.asString().equals(loginSystem.toString())).isTrue();
        }

        // 실행 입력의 접근 시스템이 전자결재뿐이면 다른 시스템 문서는 찾지 못한다.
        jdbc.update("UPDATE agent_run SET input_snapshot = jsonb_set(input_snapshot, '{accessContext,systemIds}', to_jsonb(ARRAY[?::text])) WHERE id = ?",
                eaSystem.toString(), run);
        JsonNode restricted = search(body("로그인 권한 신청 방법")).andExpect(status().isOk()).andReturn().json();
        for (JsonNode item : restricted.path("results")) {
            JsonNode systemId = item.path("evidence").path("systemId");
            assertThat(systemId.isNull() || systemId.asString().equals(eaSystem.toString())).isTrue();
        }
        var loginOnly = body("로그인 권한 신청 방법");
        loginOnly.put("systemIds", List.of(loginSystem));
        search(loginOnly).andExpect(jsonPath("$.outcome").value("DENIED")).andExpect(jsonPath("$.deniedReason").value("SYSTEM_NOT_ALLOWED"));
    }

    // ---- 선필터

    @Test
    void 미승인_평가_개발용_삭제_문서와_미승인_버전은_결과에서_제외한다() throws Exception {
        String key = "guide-ea-document-search";
        assertThat(documentKeys(search(body(EA_QUERY)).andReturn().json())).contains(key);
        String where = "WHERE source_data ->> 'documentKey' = '" + key + "'";

        for (String change : List.of("review_status = 'PENDING'", "data_split = 'EVAL'", "data_split = 'DEV'", "deleted_at = now()")) {
            jdbc.update("UPDATE knowledge_document SET " + change + " " + where);
            assertThat(documentKeys(search(body(EA_QUERY)).andReturn().json())).as(change).doesNotContain(key);
            jdbc.update("UPDATE knowledge_document SET review_status = 'APPROVED', data_split = 'RAG', deleted_at = NULL " + where);
        }
        jdbc.update("UPDATE knowledge_document_version SET review_status = 'PENDING' WHERE knowledge_document_id = "
                + "(SELECT id FROM knowledge_document " + where + ")");
        assertThat(documentKeys(search(body(EA_QUERY)).andReturn().json())).doesNotContain(key);
    }

    @Test
    void 같은_문서의_승인된_최신_버전만_찾는다() throws Exception {
        String key = "guide-ea-document-search";
        jdbc.update("INSERT INTO knowledge_document_version (knowledge_document_id, version_no, content, content_digest, source_metadata, review_status) "
                + "SELECT d.id, 2, ?, ?, v.source_metadata, 'APPROVED' FROM knowledge_document d JOIN knowledge_document_version v "
                + "ON v.knowledge_document_id = d.id WHERE d.source_data ->> 'documentKey' = ? AND v.version_no = 1",
                "전자결재 문서 조회 새 안내 본문입니다. 지난 문서를 찾는 방법을 바꿨습니다.", sha256("전자결재 문서 조회 새 안내 본문입니다. 지난 문서를 찾는 방법을 바꿨습니다."), key);

        JsonNode result = search(body(EA_QUERY)).andExpect(status().isOk()).andReturn().json();
        List<JsonNode> hits = result.path("results").valueStream().filter(r -> r.path("documentKey").asString().equals(key)).toList();
        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().path("evidence").path("documentVersion").asInt()).isEqualTo(2);
    }

    // ---- 결과·응답

    @Test
    void 결과는_근거_형식_digest_점수순을_지키고_같은_질의는_같은_순서다() throws Exception {
        JsonNode first = search(body(EA_QUERY)).andExpect(status().isOk()).andReturn().json();
        JsonNode second = search(body(EA_QUERY)).andExpect(status().isOk()).andReturn().json();
        assertThat(documentKeys(first)).isEqualTo(documentKeys(second));

        double previous = 1.0;
        for (JsonNode result : first.path("results")) {
            double score = result.path("score").asDouble();
            assertThat(score).isPositive().isLessThanOrEqualTo(previous);
            previous = score;
            assertThat(result.path("digestBasis").asString()).isEqualTo("CONTENT");
            assertThat(result.path("contentDigest").asString()).isEqualTo(sha256(result.path("content").asString()));
            JsonNode evidence = result.path("evidence");
            assertThat(evidence.path("excerpt").asString()).isEqualTo(result.path("content").asString());
            assertThat(evidence.path("origin").asString()).isEqualTo("SYNTHETIC");
            assertThat(evidence.path("applicability").asString()).isNotBlank();
            assertThat(result.path("caseIndexStatus").isNull()).isTrue();
        }
    }

    @Test
    void 검색_결과_근거는_저장된_근거의_현재_공개_검증을_통과한다() throws Exception {
        JsonNode result = search(body(EA_QUERY)).andExpect(status().isOk()).andReturn().json();
        List<KnowledgeEvidenceResponse> evidence = result.path("results").valueStream()
                .map(r -> mapper.treeToValue(r.path("evidence"), KnowledgeEvidenceResponse.class)).toList();
        assertThat(evidence).isNotEmpty();
        Inquiry stored = inquiries.findById(inquiry).orElseThrow();

        assertThat(reader.visible(mapper.writeValueAsString(evidence), stored.getRequester())).isEqualTo(evidence);
    }

    @Test
    void 일치하는_문서가_없으면_NO_RESULT이고_오류와_구분된다() throws Exception {
        search(body("zzzzqqqq wwwwxxxx")).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("NO_RESULT"))
                .andExpect(jsonPath("$.deniedReason").doesNotExist()).andExpect(jsonPath("$.results").isEmpty());
    }

    @Test
    void 저장된_원문_digest가_어긋나면_결과_없음이_아니라_503이다() throws Exception {
        jdbc.update("UPDATE knowledge_document_version SET content_digest = repeat('0', 64) WHERE knowledge_document_id = "
                + "(SELECT id FROM knowledge_document WHERE source_data ->> 'documentKey' = 'guide-ea-document-search')");

        search(body(EA_QUERY)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SEARCH_UNAVAILABLE"));
    }

    @Test
    void topK는_1에서_5까지이고_결과를_자른다() throws Exception {
        for (int topK : List.of(0, 6, -1)) {
            var request = body("전자결재 로그인 파일 문서 권한 공유");
            request.put("topK", topK);
            search(request).andExpect(status().isBadRequest());
        }
        JsonNode all = search(body("전자결재 로그인 파일 문서 권한 공유")).andExpect(status().isOk()).andReturn().json();
        assertThat(all.path("results").size()).isBetween(2, 5);
        var one = body("전자결재 로그인 파일 문서 권한 공유");
        one.put("topK", 1);
        JsonNode top = search(one).andExpect(status().isOk()).andReturn().json();
        assertThat(top.path("results").size()).isEqualTo(1);
        assertThat(top.path("results").get(0).path("documentKey").asString()).isEqualTo(all.path("results").get(0).path("documentKey").asString());
    }

    @Test
    void 잘못된_요청_형식은_400이다() throws Exception {
        for (String query : List.of("", "   ", "어떻게 하나요")) {
            var request = body(query);
            search(request).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        var unknownProblem = body(EA_QUERY);
        unknownProblem.put("problemRef", UUID.randomUUID().toString());
        search(unknownProblem).andExpect(status().isBadRequest());
        var newProblem = body(EA_QUERY);
        newProblem.put("problemRef", "new:first");
        search(newProblem).andExpect(status().isOk());
        var badKind = body(EA_QUERY);
        badKind.put("kinds", List.of("NOTE"));
        search(badKind).andExpect(status().isBadRequest());
        var emptyKinds = body(EA_QUERY);
        emptyKinds.put("kinds", List.of());
        search(emptyKinds).andExpect(status().isBadRequest());
    }

    // ---- 인증·실행 검증

    @Test
    void 내부_API는_JWT나_잘못된_서비스_토큰을_받지_않는다() throws Exception {
        for (String token : List.of("", "Bearer wrong")) {
            mvc.perform(post(path()).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(body(EA_QUERY)))).andExpect(status().isUnauthorized());
        }
        mvc.perform(post(path()).with(requester()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body(EA_QUERY)))).andExpect(status().isUnauthorized());
    }

    @Test
    void 없는_실행_이전_버전_기한초과_권한변경_완료된_실행을_거부한다() throws Exception {
        mvc.perform(post("/internal/v2/runs/" + UUID.randomUUID() + "/knowledge-searches").header("Authorization", "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body(EA_QUERY)))).andExpect(status().isNotFound());

        var stale = body(EA_QUERY);
        stale.put("inquiryVersion", inquiryVersion + 1);
        search(stale).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INQUIRY_VERSION_CONFLICT"));

        jdbc.update("UPDATE agent_run SET run_status = 'CANCELLED', completed_at = now() WHERE id = ?", run);
        search(body(EA_QUERY)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));
        jdbc.update("UPDATE agent_run SET run_status = 'RUNNING', completed_at = NULL WHERE id = ?", run);

        jdbc.update("UPDATE agent_run SET input_snapshot = jsonb_set(input_snapshot, '{expiresAt}', '\"2020-01-01T00:00:00Z\"') WHERE id = ?", run);
        search(body(EA_QUERY)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));

        jdbc.update("UPDATE agent_run SET input_snapshot = jsonb_set(input_snapshot, '{expiresAt}', to_jsonb(?::text)) WHERE id = ?",
                Instant.now().plusSeconds(300).toString(), run);
        search(body(EA_QUERY)).andExpect(status().isOk());
        jdbc.update("UPDATE member SET policy_revision = policy_revision + 1 WHERE id = ?::uuid", REQUESTER1);
        search(body(EA_QUERY)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void 실행_입력이_직원_목적이_아니면_거부한다() throws Exception {
        jdbc.update("UPDATE agent_run SET input_snapshot = jsonb_set(input_snapshot, '{accessContext,purpose}', '\"SYSTEM_WORK\"') WHERE id = ?", run);
        search(body(EA_QUERY)).andExpect(status().isForbidden());
    }

    // ---- 담당자 범위와 CASE 공개 규칙

    @Test
    void 담당자_범위_실행은_주담당자에게만_런북과_사례를_보인다() throws Exception {
        // 직원 상담의 기본 프로필에는 없는 옵션 프로필 상황을 서버가 확정한 실행 입력으로 흉내 낸다(요청으로는 만들 수 없다).
        allowStaffScope();
        String runbookTitle = jdbc.queryForObject("SELECT title FROM knowledge_document WHERE source_data ->> 'documentKey' = 'runbook-ea-document-not-listed'", String.class);

        // 문의자가 해당 시스템의 주담당자가 아니면 담당자 범위여도 STAFF 문서를 받지 못한다.
        assertThat(documentKeys(search(body(runbookTitle)).andReturn().json())).noneMatch(key -> key.startsWith("runbook-"));

        jdbc.update("UPDATE it_system SET primary_owner_member_id = ?::uuid WHERE id = ?", REQUESTER1, eaSystem);
        JsonNode found = search(body(runbookTitle)).andExpect(status().isOk()).andReturn().json();
        assertThat(documentKeys(found)).contains("runbook-ea-document-not-listed");
    }

    @Test
    void 사례는_기본_규칙에서_색인_상태_그대로_공개되고_계약_규칙에서는_사라진다() throws Exception {
        allowStaffScope();
        jdbc.update("UPDATE it_system SET primary_owner_member_id = ?::uuid WHERE id = ?", REQUESTER1, eaSystem);
        var request = body("전자결재 문서가 문서함에 안 보임 열람 권한");
        request.put("kinds", List.of("CASE"));

        JsonNode found = search(request).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("FOUND")).andReturn().json();
        JsonNode first = found.path("results").get(0);
        assertThat(first.path("evidence").path("kind").asString()).isEqualTo("CASE");
        assertThat(first.path("evidence").path("accessScope").asString()).isEqualTo("SYSTEM_STAFF");
        assertThat(first.path("evidence").path("origin").asString()).isEqualTo("SYNTHETIC");
        assertThat(first.path("evidence").path("applicability").asString()).isNotBlank();
        assertThat(first.path("digestBasis").asString()).isEqualTo("APPROVED_SNAPSHOT");
        // 색인 상태를 SUCCEEDED로 위장하지 않고 저장된 값을 그대로 보인다.
        assertThat(first.path("caseIndexStatus").path("vector").asString()).isEqualTo("PENDING");
        assertThat(first.path("caseIndexStatus").path("graph").asString()).isEqualTo("PENDING");
        // CASE의 근거 키는 사례 ID·사례 버전이고 문서 키 자리에는 사례 번호가 온다.
        UUID caseId = jdbc.queryForObject("SELECT id FROM resolved_case WHERE case_number = ?", UUID.class, first.path("documentKey").asString());
        assertThat(first.path("evidence").path("documentId").asString()).isEqualTo(caseId.toString());
        // 저장된 근거의 현재 공개 검증도 같은 규칙으로 이 근거를 받아들인다.
        KnowledgeEvidenceResponse evidence = mapper.treeToValue(first.path("evidence"), KnowledgeEvidenceResponse.class);
        Inquiry stored = inquiries.findById(inquiry).orElseThrow();
        assertThat(reader.visible(mapper.writeValueAsString(List.of(evidence)), stored.getRequester())).containsExactly(evidence);

        // 계약 규칙(INDEXED)에서는 색인이 SUCCEEDED가 아닌 사례를 찾지 못한다.
        var indexed = new KnowledgeSearchService(searchRepository, versions, assembler, new KnowledgeVisibilityPolicy(CaseVisibility.INDEXED));
        var basic = new KnowledgeSearchService(searchRepository, versions, assembler, new KnowledgeVisibilityPolicy(CaseVisibility.APPROVED_SNAPSHOT));
        var query = new KnowledgeSearchService.Query(List.of("전자결재", "문서함", "열람", "권한"), Set.of(AccessLevel.STAFF),
                Set.of(DocumentType.CASE), Set.of(eaSystem), stored.getRequester().getId(), 5);
        assertThat(basic.search(query)).isNotEmpty();
        assertThat(indexed.search(query)).isEmpty();
        // 저장된 근거의 현재 공개 검증도 같은 설정을 따른다. 한쪽만 바꾸면 검색에서 나온 근거를 이후 단계가 거부한다.
        var indexedReader = new TicketKnowledgeReader(versions, systems, storedData, new KnowledgeVisibilityPolicy(CaseVisibility.INDEXED), assembler);
        String storedEvidence = mapper.writeValueAsString(List.of(evidence));
        assertThat(indexedReader.visible(storedEvidence, stored.getRequester())).isEmpty();
        // 양쪽 색인이 모두 성공하면 계약 규칙에서도 공개된다.
        jdbc.update("UPDATE resolved_case SET vector_status = 'SUCCEEDED', graph_status = 'SUCCEEDED' WHERE it_system_id = ?", eaSystem);
        assertThat(indexed.search(query)).isNotEmpty();
        assertThat(indexedReader.visible(storedEvidence, stored.getRequester())).containsExactly(evidence);
    }

    @Test
    void 사례_승인_스냅샷이_바뀌면_503이다() throws Exception {
        allowStaffScope();
        jdbc.update("UPDATE it_system SET primary_owner_member_id = ?::uuid WHERE id = ?", REQUESTER1, eaSystem);
        jdbc.update("UPDATE knowledge_document_version SET content_digest = repeat('1', 64) WHERE knowledge_document_id IN "
                + "(SELECT id FROM knowledge_document WHERE document_type = 'CASE' AND it_system_id = ?)", eaSystem);
        var request = body("전자결재 문서가 문서함에 안 보임 열람 권한");
        request.put("kinds", List.of("CASE"));

        search(request).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SEARCH_UNAVAILABLE"));
    }

    // ---- 도우미

    String path() {
        return "/internal/v2/runs/" + run + "/knowledge-searches";
    }

    Map<String, Object> body(String query) {
        return new LinkedHashMap<>(Map.of("inquiryVersion", inquiryVersion, "problemRef", "new:first", "query", query));
    }

    /** 응답 본문을 JsonNode로도 돌려주는 결과. */
    class Searched {
        final ResultActions actions;

        Searched(ResultActions actions) {
            this.actions = actions;
        }

        Searched andExpect(org.springframework.test.web.servlet.ResultMatcher matcher) throws Exception {
            actions.andExpect(matcher);
            return this;
        }

        JsonNode json() throws Exception {
            return mapper.readTree(actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        }

        Searched andReturn() {
            return this;
        }
    }

    Searched search(Map<String, Object> body) throws Exception {
        return new Searched(mvc.perform(post(path()).header("Authorization", "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body))));
    }

    List<String> documentKeys(JsonNode response) {
        return response.path("results").valueStream().map(r -> r.path("documentKey").asString()).toList();
    }

    void allowStaffScope() {
        jdbc.update("UPDATE agent_run SET input_snapshot = jsonb_set(input_snapshot, '{accessContext,allowedDocumentScopes}', '[\"EMPLOYEE\",\"SYSTEM_STAFF\"]'::jsonb) WHERE id = ?", run);
    }

    UUID system(String code) {
        return jdbc.queryForObject("SELECT id FROM it_system WHERE code = ?", UUID.class, code);
    }

    RequestPostProcessor requester() {
        String departmentId = jdbc.queryForObject("SELECT department_id::text FROM member WHERE id = CAST(? AS uuid)", String.class, REQUESTER1);
        return jwt().jwt(token -> token.subject(REQUESTER1).claim("departmentId", departmentId).claim("roles", List.of())
                .claim("policyRevision", 1).claim("primarySystemIds", List.of()).expiresAt(Instant.now().plusSeconds(600)));
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
