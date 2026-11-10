package com.hihops.hops.agent.internal;

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

import com.hihops.hops.agent.service.InputProblemReader;
import com.hihops.hops.inquiry.repository.InquiryRepository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 실제 PostgreSQL commit·행 잠금·HTTP 서비스 인증 검사. 실제 AI 호출은 하지 않는다. */
@SpringBootTest(properties = "SPRING_SERVICE_TOKEN=" + TicketCreationApiTest.TOKEN)
@AutoConfigureMockMvc
@ActiveProfiles({"db", "test"})
class TicketCreationApiTest {
    static final String TOKEN = "dGVzdC10b2tlbi1mb3ItdGlja2V0LWNyZWF0aW9uLWFwaQ";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper mapper;
    @Autowired InputProblemReader inputs;
    @Autowired InquiryRepository inquiries;
    @Autowired PlatformTransactionManager transactionManager;
    UUID department = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    UUID owner = UUID.randomUUID();
    UUID system = UUID.randomUUID();
    UUID inquiry;
    UUID run;
    UUID message;
    UUID feature = UUID.randomUUID();

    @BeforeEach
    void seed() throws Exception {
        jdbc.update("INSERT INTO department(id,code,name) VALUES (?,?,'티켓 API 테스트')", department, "T149_" + department.toString().substring(0,8));
        for (UUID id : List.of(member, owner)) jdbc.update("""
                INSERT INTO member(id,login_id,employee_no,password_hash,name,department_id)
                VALUES (?,?,?,'unused-test-hash','가상 직원',?)
                """, id, id.toString(), id.toString(), department);
        jdbc.update("INSERT INTO it_system(id,code,name,primary_owner_member_id) VALUES (?,'COMMON_LOGIN','공통 로그인',?)", system, owner);
        jdbc.update("INSERT INTO system_feature(id,it_system_id,code,name) VALUES (?,?,'LOGIN_SSO','로그인')", feature, system);
        UUID requestId = UUID.randomUUID();
        mvc.perform(post("/api/v2/inquiries").with(jwt().jwt(t -> t.subject(member.toString())))
                .header("Idempotency-Key", requestId).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("requestId", requestId, "content", "공통 로그인에서 오류가 납니다", "reportedSystemId", system))))
                .andExpect(status().isOk());
        inquiry = jdbc.queryForObject("SELECT id FROM inquiry WHERE requester_member_id=?", UUID.class, member);
        run = jdbc.queryForObject("SELECT current_run_id FROM inquiry WHERE id=?", UUID.class, inquiry);
        message = jdbc.queryForObject("SELECT id FROM message WHERE inquiry_id=?", UUID.class, inquiry);
    }

    @AfterEach
    void cleanup() {
        if (inquiry != null) {
            jdbc.update("UPDATE inquiry SET current_run_id=NULL WHERE id=?", inquiry);
            jdbc.update("DELETE FROM ticket_assignment WHERE ticket_id IN (SELECT id FROM ticket WHERE inquiry_id=?)", inquiry);
            jdbc.update("DELETE FROM ticket WHERE inquiry_id=?", inquiry);
            jdbc.update("DELETE FROM agent_ticket_request WHERE run_id IN (SELECT id FROM agent_run WHERE inquiry_id=?)", inquiry);
            jdbc.update("DELETE FROM standard_request WHERE inquiry_problem_id IN (SELECT id FROM inquiry_problem WHERE inquiry_id=?)", inquiry);
            jdbc.update("DELETE FROM inquiry_problem WHERE inquiry_id=?", inquiry);
            jdbc.update("DELETE FROM agent_run WHERE inquiry_id=?", inquiry);
            jdbc.update("DELETE FROM message WHERE inquiry_id=?", inquiry);
            jdbc.update("DELETE FROM inquiry WHERE id=?", inquiry);
        }
        jdbc.update("DELETE FROM idempotency_key WHERE actor_member_id=?", member);
        jdbc.update("DELETE FROM system_feature WHERE it_system_id=?", system);
        jdbc.update("DELETE FROM it_system WHERE id=?", system);
        jdbc.update("DELETE FROM member WHERE id IN (?,?)", member, owner);
        jdbc.update("DELETE FROM department WHERE id=?", department);
    }

    String path() { return "/internal/v2/runs/" + run + "/ticket-creations"; }

    Map<String,Object> body(String ref) {
        var details = new LinkedHashMap<String,Object>();
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
                        List.of(Map.of("start", 0, "end", "공통 로그인에서 오류가 납니다".codePointCount(0, "공통 로그인에서 오류가 납니다".length())))))));
    }

    ResultActions create(Map<String,Object> body) throws Exception {
        return mvc.perform(post(path()).header("Authorization", "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)));
    }

    JsonNode stored(String ref) throws Exception {
        return mapper.readTree(create(body(ref)).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("STORED")).andReturn().getResponse().getContentAsString());
    }

    @Test void 새로_확인한_기능을_이전_입력의_null_대신_표준요청에_저장한다() throws Exception {
        var data = mapper.valueToTree(body("new:explicit-feature"));
        var facts = List.of(Map.of("text", "하려던 업무·화면: 로그인", "sourceMessageIds", List.of(message),
                "knowledgeEvidenceIds", List.of()));
        ((tools.jackson.databind.node.ObjectNode)data.path("problem")).set("facts", mapper.valueToTree(facts));
        ((tools.jackson.databind.node.ObjectNode)data.path("proposal").path("standardRequestSnapshot"))
                .set("confirmedFacts", mapper.valueToTree(facts));
        mvc.perform(post(path()).header("Authorization", "Bearer " + TOKEN).contentType(MediaType.APPLICATION_JSON)
                .content(data.toString())).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("STORED"));
        assertThat(jdbc.queryForObject("SELECT reported_feature_id FROM standard_request WHERE agent_run_id=?", UUID.class, run))
                .isEqualTo(feature);
    }

    @Test void 화면_확인사실이_누락되어도_검증된_USER_구간의_명시적_화면명을_저장한다() throws Exception {
        String text = "로그인 화면입니다. 오류가 납니다.";
        jdbc.update("UPDATE message SET content=? WHERE id=?", text, message);
        jdbc.update("UPDATE agent_run SET input_snapshot=jsonb_set(input_snapshot,'{messages,0,content}',to_jsonb(?::text)) WHERE id=?", text, run);
        var command = body("new:raw-feature");
        command.put("messageLinks", List.of(Map.of("messageId", message, "revision", 1,
                "spans", List.of(Map.of("start", 0, "end", text.codePointCount(0, text.length()))))));
        create(command).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("STORED"));
        assertThat(jdbc.queryForObject("SELECT reported_feature_id FROM standard_request WHERE agent_run_id=?", UUID.class, run))
                .isEqualTo(feature);
    }

    @Test
    void 다른_문제의_검색_범위를_첨부하면_업무를_저장하지_않는다() throws Exception {
        var command = body("new:target");
        command.put("knowledgeScope", Map.of("problemRef", "new:other", "evidenceIds", List.of()));
        create(command).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket WHERE inquiry_id=?", Integer.class, inquiry)).isZero();
    }

    @Test
    void 생성과_배정을_commit하고_재전송과_조회는_같은_티켓을_반환한다() throws Exception {
        var first = stored("new:1");
        var second = stored("new:1");
        assertThat(first.get("ticketId")).isEqualTo(second.get("ticketId"));
        assertThat(first.get("workItemId")).isEqualTo(first.get("problemId"));
        assertThat(first.get("assigneeId").asString()).isEqualTo(owner.toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket WHERE inquiry_id=?", Integer.class, inquiry)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_assignment WHERE ticket_id=?::uuid", Integer.class, first.get("ticketId").asString())).isEqualTo(1);
        mvc.perform(get(path()+"/new:1").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ticketId").value(first.get("ticketId").asString()));
    }

    @Test
    void 같은_참조의_다른_요청_본문을_거부한다() throws Exception {
        stored("new:1");
        var different = body("new:1");
        different.put("inquiryVersion", 2);
        create(different).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void 내부_API는_JWT나_잘못된_서비스_토큰을_받지_않는다() throws Exception {
        for (String token : List.of("", "Bearer wrong")) mvc.perform(post(path()).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body("new:1"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(path()+"/new:1").with(jwt().jwt(t -> t.subject(member.toString())))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v2/tickets").header("Authorization", "Bearer " + TOKEN)).andExpect(status().isUnauthorized());
    }

    @Test
    void 이전_버전_기한초과_현재아닌실행_권한변경을_거부한다() throws Exception {
        var stale = body("new:1"); stale.put("inquiryVersion", 2);
        create(stale).andExpect(status().isConflict());
        jdbc.update("UPDATE agent_run SET input_snapshot=jsonb_set(input_snapshot,'{expiresAt}','\"2020-01-01T00:00:00Z\"') WHERE id=?", run);
        create(body("new:1")).andExpect(status().isConflict());
        jdbc.update("UPDATE member SET policy_revision=policy_revision+1 WHERE id=?", member);
        create(body("new:1")).andExpect(status().isForbidden());
    }

    @Test
    void 배정실패는_미생성으로_보존하고_같은_재시도는_중복소비하지_않는다() throws Exception {
        jdbc.update("UPDATE member SET deleted_at=now() WHERE id=?", owner);
        create(body("new:1")).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("NOT_CREATED"))
                .andExpect(jsonPath("$.attempt").value(1)).andExpect(jsonPath("$.retryable").value(true));
        retry(1).andExpect(status().isOk()).andExpect(jsonPath("$.attempt").value(2));
        retry(1).andExpect(status().isOk()).andExpect(jsonPath("$.attempt").value(2));
        jdbc.update("UPDATE member SET deleted_at=NULL WHERE id=?", owner);
        retry(2).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("STORED"));
        retry(2).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("STORED"));
    }

    ResultActions retry(int attempt) throws Exception {
        return mvc.perform(post(path()+"/new:1/retries").header("Authorization", "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("inquiryVersion", 1, "expectedAttempt", attempt))));
    }

    @Test
    void 최대_세번까지만_생성을_시도한다() throws Exception {
        jdbc.update("UPDATE member SET deleted_at=now() WHERE id=?", owner);
        create(body("new:1")).andExpect(status().isOk());
        retry(1).andExpect(status().isOk());
        retry(2).andExpect(status().isOk()).andExpect(jsonPath("$.attempt").value(3)).andExpect(jsonPath("$.retryable").value(false));
        retry(3).andExpect(status().isConflict());
    }

    @Test
    void 동시_생성요청도_티켓을_하나만_만든다() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var jobs = new ArrayList<java.util.concurrent.Future<JsonNode>>();
            for (int n=0; n<2; n++) jobs.add(pool.submit(() -> stored("new:1")));
            assertThat(jobs.get(0).get().get("ticketId")).isEqualTo(jobs.get(1).get().get("ticketId"));
        }
    }

    @Test
    void 생성후_AI실패에도_조회와_다음입력에_저장결과가_남는다() throws Exception {
        var result = stored("new:1");
        jdbc.update("UPDATE agent_run SET run_status='FAILED',completed_at=now() WHERE id=?", run);
        mvc.perform(get(path()+"/new:1").header("Authorization", "Bearer " + TOKEN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketId").value(result.get("ticketId").asString()));
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            var input = inputs.read(inquiries.findById(inquiry).orElseThrow(), List.of());
            assertThat(input.existingWorkItems()).hasSize(1);
            assertThat(input.existingWorkItems().getFirst().outcome()).isEqualTo("STORED");
            assertThat(input.problems().getFirst().standardRequest().confirmedFacts()).hasSize(1);
        });
    }

    @Test
    void 임의의_메시지출처와_지원하지않는시스템을_거부하고_문제를_남기지않는다() throws Exception {
        var data = mapper.valueToTree(body("new:1"));
        var changed = mapper.readTree(data.toString().replace(message.toString(), UUID.randomUUID().toString()));
        mvc.perform(post(path()).header("Authorization", "Bearer " + TOKEN).contentType(MediaType.APPLICATION_JSON).content(changed.toString()))
                .andExpect(status().isBadRequest());
        var unsupported = mapper.readTree(data.toString().replace(system.toString(), UUID.randomUUID().toString()));
        mvc.perform(post(path()).header("Authorization", "Bearer " + TOKEN).contentType(MediaType.APPLICATION_JSON).content(unsupported.toString()))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inquiry_problem WHERE inquiry_id=?", Integer.class, inquiry)).isZero();
    }

    @Test
    void 하나의_실패가_다른_문제의_성공을_취소하지않는다() throws Exception {
        stored("new:1");
        jdbc.update("UPDATE member SET deleted_at=now() WHERE id=?", owner);
        create(body("new:2")).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("NOT_CREATED"));
        mvc.perform(get(path()+"/new:1").header("Authorization", "Bearer " + TOKEN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("STORED"));
    }

    @Test
    void 활성실행이_아니면_새_생성을_거부하고_조회실패가_생성을_시작하지않는다() throws Exception {
        mvc.perform(get(path()+"/new:1").header("Authorization", "Bearer " + TOKEN)).andExpect(status().isNotFound());
        jdbc.update("UPDATE inquiry SET current_run_id=NULL WHERE id=?", inquiry);
        create(body("new:1")).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket WHERE inquiry_id=?", Integer.class, inquiry)).isZero();
    }

    @Test
    void 배정_저장_실패시_티켓과_journal도_함께_롤백된다() throws Exception {
        // 실제 FK/타입 오류 대신 테스트 전용 트리거로 마지막 배정 쓰기만 실패시킨다.
        jdbc.execute("CREATE FUNCTION ticket149_reject_assignment() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic assignment failure'; END $$");
        jdbc.execute("CREATE TRIGGER ticket149_reject_assignment BEFORE INSERT ON ticket_assignment FOR EACH ROW EXECUTE FUNCTION ticket149_reject_assignment()");
        try {
            create(body("new:1")).andExpect(status().is5xxServerError());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket WHERE inquiry_id=?", Integer.class, inquiry)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_ticket_request WHERE run_id=?", Integer.class, run)).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER ticket149_reject_assignment ON ticket_assignment");
            jdbc.execute("DROP FUNCTION ticket149_reject_assignment()");
        }
    }

    @Test
    void 실행_도구예산이_더_작으면_생성과_재시도를_더_하지않는다() throws Exception {
        jdbc.update("UPDATE agent_run SET input_snapshot=jsonb_set(input_snapshot,'{limits,maxToolCalls}','1') WHERE id=?", run);
        jdbc.update("UPDATE member SET deleted_at=now() WHERE id=?", owner);
        create(body("new:1")).andExpect(status().isOk()).andExpect(jsonPath("$.retryable").value(false));
        retry(1).andExpect(status().isConflict());
        create(body("new:2")).andExpect(status().isTooManyRequests());
    }

    @Test
    void 메시지의_잘못된차수나_원문범위밖_구간을_거부한다() throws Exception {
        var data = body("new:1");
        data.put("messageLinks", List.of(Map.of("messageId", message, "revision", 2, "spans", List.of(Map.of("start", 0, "end", 6)))));
        create(data).andExpect(status().isBadRequest());
        data.put("messageLinks", List.of(Map.of("messageId", message, "revision", 1, "spans", List.of(Map.of("start", 0, "end", 999)))));
        create(data).andExpect(status().isBadRequest());
        data.put("messageLinks", List.of());
        create(data).andExpect(status().isBadRequest());
    }
}
