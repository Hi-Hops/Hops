package com.hihops.hops.inquiry.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

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
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.databind.json.JsonMapper;

/**
 * 티켓 생성 작업 재시도 (WIRE_CONTRACT 12.2 POST /work-items/{workItemId}/retries, 원 문의 소유자·NOT_CREATED만).
 *
 * <p>실제 PostgreSQL commit·행 잠금을 쓰므로 거래 밖에서 실행하고, 넣은 행은 끝나면 지운다.
 * 미생성 작업은 AI 내부 API가 배정 실패로 남긴 것과 같은 방법(책임자 삭제)으로 만든다.
 */
@SpringBootTest(properties = "SPRING_SERVICE_TOKEN=" + WorkItemRetryControllerTest.TOKEN)
@AutoConfigureMockMvc
@ActiveProfiles({"db", "test"})
class WorkItemRetryControllerTest {
    static final String TOKEN = "dGVzdC10b2tlbi1mb3ItdGlja2V0LWNyZWF0aW9uLWFwaQ";
    private static final String CONTENT = "공통 로그인에서 오류가 납니다";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper mapper;

    UUID department = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    UUID owner = UUID.randomUUID();
    UUID system = UUID.randomUUID();
    UUID inquiry;
    UUID run;
    UUID message;

    @BeforeEach
    void seed() throws Exception {
        jdbc.update("INSERT INTO department(id,code,name) VALUES (?,?,'작업 재시도 테스트')", department, "WI02_" + department.toString().substring(0, 8));
        for (UUID id : List.of(member, other, owner)) jdbc.update("""
                INSERT INTO member(id,login_id,employee_no,password_hash,name,department_id)
                VALUES (?,?,?,'unused-test-hash','가상 직원',?)
                """, id, id.toString(), id.toString(), department);
        jdbc.update("INSERT INTO it_system(id,code,name,primary_owner_member_id) VALUES (?,'COMMON_LOGIN','공통 로그인',?)", system, owner);
        UUID requestId = UUID.randomUUID();
        mvc.perform(post("/api/v2/inquiries").with(jwt().jwt(t -> t.subject(member.toString())))
                .header("Idempotency-Key", requestId).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("requestId", requestId, "content", CONTENT, "reportedSystemId", system))))
                .andExpect(status().isOk());
        inquiry = jdbc.queryForObject("SELECT id FROM inquiry WHERE requester_member_id=?", UUID.class, member);
        run = jdbc.queryForObject("SELECT current_run_id FROM inquiry WHERE id=?", UUID.class, inquiry);
        message = jdbc.queryForObject("SELECT id FROM message WHERE inquiry_id=?", UUID.class, inquiry);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("UPDATE inquiry SET current_run_id=NULL WHERE id=?", inquiry);
        jdbc.update("DELETE FROM ticket_assignment WHERE ticket_id IN (SELECT id FROM ticket WHERE inquiry_id=?)", inquiry);
        jdbc.update("DELETE FROM ticket WHERE inquiry_id=?", inquiry);
        jdbc.update("DELETE FROM agent_ticket_request WHERE run_id IN (SELECT id FROM agent_run WHERE inquiry_id=?)", inquiry);
        jdbc.update("DELETE FROM standard_request WHERE inquiry_problem_id IN (SELECT id FROM inquiry_problem WHERE inquiry_id=?)", inquiry);
        jdbc.update("DELETE FROM inquiry_problem WHERE inquiry_id=?", inquiry);
        jdbc.update("DELETE FROM agent_run WHERE inquiry_id=?", inquiry);
        jdbc.update("DELETE FROM message WHERE inquiry_id=?", inquiry);
        jdbc.update("DELETE FROM inquiry WHERE id=?", inquiry);
        jdbc.update("DELETE FROM idempotency_key WHERE actor_member_id IN (?,?)", member, other);
        jdbc.update("DELETE FROM it_system WHERE id=?", system);
        jdbc.update("DELETE FROM member WHERE id IN (?,?,?)", member, other, owner);
        jdbc.update("DELETE FROM department WHERE id=?", department);
    }

    // ---- 준비: AI 내부 API가 만든 것과 같은 상태 ----

    Map<String, Object> body(String ref) {
        var details = new LinkedHashMap<String, Object>();
        for (String key : List.of("taskOrScreen", "requestedAccess", "businessPurpose", "expectedBehavior", "actualBehavior", "occurredAt", "currentWorkflow", "desiredChange")) details.put(key, null);
        details.put("actualBehavior", "로그인 오류");
        details.put("acceptanceCriteria", List.of());
        var facts = List.of(Map.of("text", "사용자가 로그인 오류를 보고함", "sourceMessageIds", List.of(message), "knowledgeEvidenceIds", List.of()));
        var problem = Map.of("problemRef", ref, "summary", "로그인 오류 조사", "intent", "OBSERVED_SYMPTOM",
                "processingType", "INVESTIGATION", "reportedSystemCandidateId", system, "facts", facts,
                "assumptions", List.of(), "unknowns", List.of("실제 원인"), "details", details);
        var snapshot = Map.of("summary", "로그인 오류 조사", "confirmedFacts", facts, "assumptions", List.of(),
                "unknowns", List.of("실제 원인"), "responsibleSystemCandidateId", system, "responsibilityEvidenceIds", List.of(),
                "details", details, "knowledge", List.of());
        var proposal = Map.of("problemRef", ref, "title", "로그인 오류 조사", "processingType", "INVESTIGATION",
                "responsibleSystemCandidateId", system, "evidenceIds", List.of(), "standardRequestSnapshot", snapshot);
        return new LinkedHashMap<>(Map.of("inquiryVersion", 1, "problem", problem, "proposal", proposal,
                "messageLinks", List.of(Map.of("messageId", message, "revision", 1, "spans",
                        List.of(Map.of("start", 0, "end", CONTENT.codePointCount(0, CONTENT.length())))))));
    }

    /** 책임자가 없는 동안 AI가 생성을 시도해 NOT_CREATED가 된 작업을 만들고 workItemId를 돌려준다. 책임자는 그대로 없다. */
    UUID notCreated() throws Exception {
        jdbc.update("UPDATE member SET deleted_at=now() WHERE id=?", owner);
        mvc.perform(post("/internal/v2/runs/" + run + "/ticket-creations").header("Authorization", "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body("new:1"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("NOT_CREATED"));
        return jdbc.queryForObject("SELECT id FROM inquiry_problem WHERE inquiry_id=?", UUID.class, inquiry);
    }

    void ownerReturns() {
        jdbc.update("UPDATE member SET deleted_at=NULL WHERE id=?", owner);
    }

    /** 분석 실행이 이미 끝난 뒤의 상태. 사용자 재시도는 실행이 끝났어도 가능해야 한다. */
    void runEnded() {
        jdbc.update("UPDATE agent_run SET run_status='SUCCEEDED', started_at=now() - interval '1 minute', completed_at=now() WHERE id=?", run);
        jdbc.update("UPDATE inquiry SET current_run_id=NULL WHERE id=?", inquiry);
    }

    // ---- 호출 ----

    ResultActions retry(UUID who, UUID workItem, UUID key, Map<String, Object> content) throws Exception {
        return mvc.perform(post("/api/v2/work-items/" + workItem + "/retries").with(jwt().jwt(t -> t.subject(who.toString())))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(content)));
    }

    ResultActions retry(UUID workItem, int version) throws Exception {
        UUID key = UUID.randomUUID();
        return retry(member, workItem, key, Map.of("requestId", key, "expectedInquiryVersion", version));
    }

    ResultActions read(UUID who, UUID workItem) throws Exception {
        return mvc.perform(get("/api/v2/work-items/" + workItem).with(jwt().jwt(t -> t.subject(who.toString()))));
    }

    int tickets() {
        return jdbc.queryForObject("SELECT count(*) FROM ticket WHERE inquiry_id=?", Integer.class, inquiry);
    }

    // ---- 성공 ----

    @Test
    void 미생성_작업을_다시_시도하면_같은_workItemId로_티켓이_저장된다() throws Exception {
        UUID workItem = notCreated();
        ownerReturns();
        retry(workItem, 1).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.resource.type").value("WORK_ITEM")).andExpect(jsonPath("$.resource.id").value(workItem.toString()));
        read(member, workItem).andExpect(status().isOk()).andExpect(jsonPath("$.workItemId").value(workItem.toString()))
                .andExpect(jsonPath("$.outcome").value("STORED")).andExpect(jsonPath("$.ticketId").isNotEmpty());
        assertThat(tickets()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ticket_assignment a JOIN ticket t ON t.id=a.ticket_id
                WHERE t.inquiry_id=? AND a.assignee_member_id=? AND a.ended_at IS NULL
                """, Integer.class, inquiry, owner)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inquiry_problem WHERE inquiry_id=?", Integer.class, inquiry)).isEqualTo(1);
    }

    @Test
    void 분석_실행이_끝난_뒤에도_다시_시도할_수_있다() throws Exception {
        UUID workItem = notCreated();
        runEnded();
        ownerReturns();
        retry(workItem, 1).andExpect(status().isOk());
        read(member, workItem).andExpect(jsonPath("$.outcome").value("STORED"));
        assertThat(tickets()).isEqualTo(1);
    }

    @Test
    void 책임자가_아직_없으면_접수하되_계속_미생성이고_티켓은_만들지_않는다() throws Exception {
        UUID workItem = notCreated();
        retry(workItem, 1).andExpect(status().isOk()).andExpect(jsonPath("$.resource.id").value(workItem.toString()));
        read(member, workItem).andExpect(jsonPath("$.outcome").value("NOT_CREATED"))
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));
        assertThat(tickets()).isZero();
        ownerReturns();
        retry(workItem, 1).andExpect(status().isOk());
        read(member, workItem).andExpect(jsonPath("$.outcome").value("STORED"));
    }

    @Test
    void 같은_요청_키는_처음_결과를_돌려주고_티켓을_더_만들지_않는다() throws Exception {
        UUID workItem = notCreated();
        ownerReturns();
        UUID key = UUID.randomUUID();
        Map<String, Object> content = Map.of("requestId", key, "expectedInquiryVersion", 1);
        var first = retry(member, workItem, key, content).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var again = retry(member, workItem, key, content).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(again).path("operationId")).isEqualTo(mapper.readTree(first).path("operationId"));
        assertThat(tickets()).isEqualTo(1);
    }

    @Test
    void 이미_저장된_작업은_기존_참조를_돌려준다() throws Exception {
        UUID workItem = notCreated();
        ownerReturns();
        retry(workItem, 1).andExpect(status().isOk());
        retry(workItem, 1).andExpect(status().isOk()).andExpect(jsonPath("$.resource.type").value("WORK_ITEM"))
                .andExpect(jsonPath("$.resource.id").value(workItem.toString()));
        assertThat(tickets()).isEqualTo(1);
    }

    @Test
    void 서로_다른_키의_동시_재시도도_티켓은_하나다() throws Exception {
        UUID workItem = notCreated();
        ownerReturns();
        var pool = Executors.newFixedThreadPool(4);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 4; i++) futures.add(pool.submit(() -> retry(workItem, 1).andReturn().getResponse().getStatus()));
            for (var future : futures) assertThat(future.get()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
        assertThat(tickets()).isEqualTo(1);
    }

    // ---- 거절 ----

    @Test
    void 남의_작업과_없는_작업은_404다() throws Exception {
        UUID workItem = notCreated();
        ownerReturns();
        UUID key = UUID.randomUUID();
        retry(other, workItem, key, Map.of("requestId", key, "expectedInquiryVersion", 1)).andExpect(status().isNotFound());
        retry(UUID.randomUUID(), 1).andExpect(status().isNotFound());
        assertThat(tickets()).isZero();
    }

    @Test
    void 삭제된_문의의_작업은_404다() throws Exception {
        UUID workItem = notCreated();
        ownerReturns();
        jdbc.update("UPDATE inquiry SET deleted_at=now() WHERE id=?", inquiry);
        retry(workItem, 1).andExpect(status().isNotFound());
    }

    @Test
    void 화면이_본_문의_버전이_다르면_409다() throws Exception {
        UUID workItem = notCreated();
        ownerReturns();
        retry(workItem, 2).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INQUIRY_VERSION_CONFLICT"));
        assertThat(tickets()).isZero();
    }

    @Test
    void 문의가_바뀌어_입력_버전이_달라졌으면_현재_버전으로_보내도_409다() throws Exception {
        UUID workItem = notCreated();
        ownerReturns();
        jdbc.update("UPDATE inquiry SET version=2 WHERE id=?", inquiry);
        retry(workItem, 2).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INQUIRY_VERSION_CONFLICT"));
        assertThat(tickets()).isZero();
    }

    @Test
    void 저장_여부를_모르는_작업은_다시_만들지_않고_409다() throws Exception {
        UUID workItem = notCreated();
        ownerReturns();
        jdbc.update("UPDATE inquiry_problem SET creation_outcome='UNKNOWN' WHERE id=?", workItem);
        retry(workItem, 1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORK_ITEM_NOT_RETRYABLE"));
        assertThat(tickets()).isZero();
    }

    @Test
    void 진행_중인_작업은_409다() throws Exception {
        UUID workItem = notCreated();
        ownerReturns();
        jdbc.update("UPDATE inquiry_problem SET creation_outcome='PENDING' WHERE id=?", workItem);
        retry(workItem, 1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORK_ITEM_NOT_RETRYABLE"));
        assertThat(tickets()).isZero();
    }

    @Test
    void 삭제된_티켓_행이_남은_작업은_다시_만들_수_없어_409다() throws Exception {
        UUID workItem = notCreated();
        ownerReturns();
        retry(workItem, 1).andExpect(status().isOk());
        jdbc.update("UPDATE ticket SET deleted_at=now() WHERE inquiry_id=?", inquiry);
        retry(workItem, 1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORK_ITEM_NOT_RETRYABLE"));
        assertThat(tickets()).isEqualTo(1);
    }

    @Test
    void 끝난_문의의_작업은_409다() throws Exception {
        UUID workItem = notCreated();
        ownerReturns();
        jdbc.update("UPDATE inquiry SET inquiry_status='CLOSED' WHERE id=?", inquiry);
        retry(workItem, 1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORK_ITEM_NOT_RETRYABLE"));
        assertThat(tickets()).isZero();
    }

    @Test
    void 전달_기록이_없는_미생성_작업은_409다() throws Exception {
        UUID workItem = notCreated();
        ownerReturns();
        jdbc.update("DELETE FROM agent_ticket_request WHERE problem_id=?", workItem);
        retry(workItem, 1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORK_ITEM_NOT_RETRYABLE"));
        assertThat(tickets()).isZero();
    }

    @Test
    void 같은_키를_다른_본문에_쓰면_409다() throws Exception {
        UUID workItem = notCreated();
        ownerReturns();
        UUID key = UUID.randomUUID();
        retry(member, workItem, key, Map.of("requestId", key, "expectedInquiryVersion", 1)).andExpect(status().isOk());
        retry(member, workItem, key, Map.of("requestId", key, "expectedInquiryVersion", 2))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void 본문이_잘못되면_400이다() throws Exception {
        UUID workItem = notCreated();
        ownerReturns();
        UUID key = UUID.randomUUID();
        retry(member, workItem, key, Map.of("expectedInquiryVersion", 1)).andExpect(status().isBadRequest());
        retry(member, workItem, key, Map.of("requestId", key)).andExpect(status().isBadRequest());
        retry(member, workItem, key, Map.of("requestId", key, "expectedInquiryVersion", 0)).andExpect(status().isBadRequest());
        retry(member, workItem, key, Map.of("requestId", UUID.randomUUID(), "expectedInquiryVersion", 1)).andExpect(status().isBadRequest());
        assertThat(tickets()).isZero();
    }

    @Test
    void 로그인하지_않으면_401이다() throws Exception {
        UUID key = UUID.randomUUID();
        mvc.perform(post("/api/v2/work-items/" + UUID.randomUUID() + "/retries").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("requestId", key, "expectedInquiryVersion", 1))))
                .andExpect(status().isUnauthorized());
    }
}
