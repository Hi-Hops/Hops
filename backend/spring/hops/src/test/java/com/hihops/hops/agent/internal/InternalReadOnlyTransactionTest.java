package com.hihops.hops.agent.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.json.JsonMapper;

/**
 * 지식 검색·관계 조회가 **실제 요청 거래(읽기 전용)**에서 동작하는지 확인한다.
 *
 * <p>InternalRunApiTestBase 기반 테스트는 시드를 되돌리려고 쓰기 거래 안에서 돌아서 읽기 전용 거래의 제약
 * (예: 행 잠금 조회 금지)이 가려진다. 로컬 통합에서 문의 행 잠금 조회가 읽기 전용 거래에서 500이 된 적이 있어,
 * 이 테스트는 거래 밖에서 최소 데이터를 직접 넣고 요청을 보낸 뒤 지운다(TicketCreationApiTest와 같은 방식).
 */
@SpringBootTest(properties = "SPRING_SERVICE_TOKEN=" + InternalRunApiTestBase.TOKEN)
@AutoConfigureMockMvc
@ActiveProfiles({"db", "test"})
class InternalReadOnlyTransactionTest {
    private static final String CONTENT = "읽기 전용 거래 확인용 안내 본문입니다. 문서 조회 방법을 설명합니다.";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper mapper;

    UUID department = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    UUID owner = UUID.randomUUID();
    UUID system = UUID.randomUUID();
    UUID feature = UUID.randomUUID();
    UUID document = UUID.randomUUID();
    UUID version = UUID.randomUUID();
    UUID symptom = UUID.randomUUID();
    UUID cause = UUID.randomUUID();
    UUID check = UUID.randomUUID();
    UUID question = UUID.randomUUID();
    UUID inquiry;
    UUID run;
    String suffix = UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void seed() throws Exception {
        jdbc.update("INSERT INTO department(id,code,name) VALUES (?,?,'읽기 전용 테스트')", department, "RO_" + suffix);
        for (UUID id : List.of(member, owner)) {
            jdbc.update("INSERT INTO member(id,login_id,employee_no,password_hash,name,department_id) VALUES (?,?,?,'unused-test-hash','가상 직원',?)",
                    id, id.toString(), id.toString(), department);
        }
        jdbc.update("INSERT INTO it_system(id,code,name,primary_owner_member_id) VALUES (?,'COMMON_LOGIN','공통 로그인',?)", system, owner);
        jdbc.update("INSERT INTO system_feature(id,it_system_id,code,name) VALUES (?,?,'LOGIN_SSO','로그인')", feature, system);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(CONTENT.getBytes(StandardCharsets.UTF_8)));
        jdbc.update("INSERT INTO knowledge_document(id,title,document_type,it_system_id,access_level,data_split,review_status) "
                + "VALUES (?,'읽기 전용 확인 가이드','EMPLOYEE_GUIDE',?,'EMPLOYEE','RAG','APPROVED')", document, system);
        jdbc.update("INSERT INTO knowledge_document_version(id,knowledge_document_id,version_no,content,content_digest,source_metadata,review_status) "
                + "VALUES (?,?,1,?,?,'{}'::jsonb,'APPROVED')", version, document, CONTENT, digest);
        String common = "access_level,review_status,source_document_version_id";
        jdbc.update("INSERT INTO relation_symptom(id,catalog_key,label,it_system_id,system_feature_id," + common + ") VALUES (?,?,'로그인 화면이 반복됨',?,?,'EMPLOYEE','APPROVED',?)",
                symptom, "sym-ro-" + suffix, system, feature, version);
        jdbc.update("INSERT INTO relation_cause(id,catalog_key,label,it_system_id," + common + ") VALUES (?,?,'내부 원인',?,'STAFF','APPROVED',?)",
                cause, "cause-ro-" + suffix, system, version);
        jdbc.update("INSERT INTO relation_check_item(id,catalog_key,label,it_system_id,employee_disclosable," + common + ") VALUES (?,?,'확인 항목',?,true,'STAFF','APPROVED',?)",
                check, "check-ro-" + suffix, system, version);
        jdbc.update("INSERT INTO relation_question_template(id,catalog_key,check_item_id,question_text,employee_disclosable," + common + ") VALUES (?,?,?,'어느 브라우저를 쓰셨나요?',true,'EMPLOYEE','APPROVED',?)",
                question, "q-ro-" + suffix, check, version);
        jdbc.update("INSERT INTO relation_symptom_cause(symptom_id,cause_id," + common + ") VALUES (?,?,'STAFF','APPROVED',?)", symptom, cause, version);
        jdbc.update("INSERT INTO relation_cause_check(cause_id,check_item_id," + common + ") VALUES (?,?,'STAFF','APPROVED',?)", cause, check, version);

        UUID requestId = UUID.randomUUID();
        mvc.perform(post("/api/v2/inquiries").with(jwt().jwt(t -> t.subject(member.toString())))
                .header("Idempotency-Key", requestId).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("requestId", requestId, "content", "문서 조회 방법을 알려주세요", "reportedSystemId", system))))
                .andExpect(status().isOk());
        inquiry = jdbc.queryForObject("SELECT id FROM inquiry WHERE requester_member_id=?", UUID.class, member);
        run = jdbc.queryForObject("SELECT current_run_id FROM inquiry WHERE id=?", UUID.class, inquiry);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM relation_cause_check WHERE cause_id=?", cause);
        jdbc.update("DELETE FROM relation_symptom_cause WHERE symptom_id=?", symptom);
        jdbc.update("DELETE FROM relation_question_template WHERE id=?", question);
        jdbc.update("DELETE FROM relation_check_item WHERE id=?", check);
        jdbc.update("DELETE FROM relation_cause WHERE id=?", cause);
        jdbc.update("DELETE FROM relation_symptom WHERE id=?", symptom);
        jdbc.update("DELETE FROM knowledge_document_version WHERE id=?", version);
        jdbc.update("DELETE FROM knowledge_document WHERE id=?", document);
        if (inquiry != null) {
            jdbc.update("UPDATE inquiry SET current_run_id=NULL WHERE id=?", inquiry);
            jdbc.update("DELETE FROM agent_run WHERE inquiry_id=?", inquiry);
            jdbc.update("DELETE FROM message WHERE inquiry_id=?", inquiry);
            jdbc.update("DELETE FROM inquiry WHERE id=?", inquiry);
        }
        jdbc.update("DELETE FROM idempotency_key WHERE actor_member_id=?", member);
        jdbc.update("DELETE FROM system_feature WHERE id=?", feature);
        jdbc.update("DELETE FROM it_system WHERE id=?", system);
        jdbc.update("DELETE FROM member WHERE id IN (?,?)", member, owner);
        jdbc.update("DELETE FROM department WHERE id=?", department);
    }

    @Test
    void 지식_검색은_읽기_전용_거래에서_문의_행_잠금_없이_동작한다() throws Exception {
        mvc.perform(post("/internal/v2/runs/" + run + "/knowledge-searches").header("Authorization", "Bearer " + InternalRunApiTestBase.TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("inquiryVersion", 1, "problemRef", "new:first", "query", "문서 조회 방법"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("FOUND"))
                .andExpect(jsonPath("$.results[0].documentKey").value(document.toString()));
    }

    @Test
    void 관계_조회는_읽기_전용_거래에서_동작하고_직원에게는_질문만_나간다() throws Exception {
        String body = mvc.perform(post("/internal/v2/runs/" + run + "/relation-searches").header("Authorization", "Bearer " + InternalRunApiTestBase.TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("inquiryVersion", 1, "problemRef", "new:first", "keywords", List.of("로그인 화면 반복")))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("FOUND"))
                .andExpect(jsonPath("$.symptoms[0].checkItems[0].questions[0].text").value("어느 브라우저를 쓰셨나요?"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("내부 원인").doesNotContain("cause-ro-");
    }
}
