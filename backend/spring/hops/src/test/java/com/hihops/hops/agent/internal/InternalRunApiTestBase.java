package com.hihops.hops.agent.internal;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 내부 읽기 API(지식 검색·관계 조회) 통합 테스트의 공통 준비. 시드(조직·직원·시스템·지식 문서·사례·관계)를 거래 안에서 적재하고
 * 끝나면 되돌린다. 시드 문의(SEED-Q-001)의 상담 실행을 접수된 현재 실행으로 되돌려 둔다.
 * 문의 접수 API는 거래 밖에서만 호출할 수 있어 실행 입력은 AnalysisInputFactory가 만드는 것과 같은 키로 직접 만든다.
 */
@SpringBootTest(properties = "SPRING_SERVICE_TOKEN=" + InternalRunApiTestBase.TOKEN)
@AutoConfigureMockMvc
@ActiveProfiles({"db", "test"})
@Transactional
abstract class InternalRunApiTestBase {
    static final String TOKEN = "dGVzdC10b2tlbi1mb3ItdGlja2V0LWNyZWF0aW9uLWFwaQ";
    static final String REQUESTER1 = "a0000000-0000-7000-8000-000000000101";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper mapper;

    UUID inquiry;
    UUID run;
    UUID eaSystem;
    UUID loginSystem;
    UUID fileSystem;
    int inquiryVersion;

    /** 호출할 내부 API의 경로 끝(예: knowledge-searches). */
    abstract String endpoint();

    @BeforeEach
    void seedAndStartRun() throws Exception {
        // R__ 파일은 이름 순으로 실행된다. 본문이 $kb$ 인용 안에 세미콜론을 포함하므로 문장을 나누지 않고 서버에 통째로 보낸다.
        for (String file : List.of("R__seed_dev_department_member.sql", "R__seed_dev_it_system.sql", "R__seed_dev_knowledge.sql",
                "R__seed_dev_knowledge_cases.sql", "R__seed_dev_relations.sql")) {
            jdbc.execute(new ClassPathResource("db/seed/" + file).getContentAsString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
        }
        eaSystem = system("ELECTRONIC_APPROVAL");
        loginSystem = system("COMMON_LOGIN");
        fileSystem = system("FILE_SHARING");
        startRun();
    }

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

    String path() {
        return "/internal/v2/runs/" + run + "/" + endpoint();
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

    /** 담당자 범위 실행(옵션 프로필)을 서버가 확정한 실행 입력으로 흉내 낸다. 요청으로는 만들 수 없다. */
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
}
