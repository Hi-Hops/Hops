package com.hihops.hops.inquiry.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.hihops.hops.agent.service.AgentRunQueued;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 문의 생성(POST /api/v2/inquiries)과 실행 조회·재분석(GET /api/v2/runs/{runId}, POST /api/v2/runs/{runId}/retries)을
 * 실제 테스트 DB로 확인한다 (WIRE_CONTRACT 12.2·12.8).
 *
 * <p>문의 생성은 멱등 처리 때문에 트랜잭션 밖에서 실행되고 실제로 commit한다. 그래서 {@code @Transactional}로 되돌리지 않고
 * 넣은 행을 {@link #cleanup()}에서 지운다. 테스트 DB에는 다른 시스템 행이 있을 수 있어 시스템 목록은 포함 여부만 본다.
 *
 * <p>시스템 코드는 계약 코드(SystemCode)만 응답할 수 있다. 다른 테스트가 트랜잭션 안에서 넣는
 * ELECTRONIC_APPROVAL·FILE_SHARING과 겹치지 않도록 COMMON_LOGIN을 쓴다(code는 유일 값).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"db", "test"})
@RecordApplicationEvents
class InquiryCreateControllerTest {

    private static final String PATH = "/api/v2/inquiries";
    private static final Set<String> AI_RUN_REQUEST_FIELDS = Set.of("contractVersion", "runId", "kind", "inquiryId",
            "inquiryVersion", "answerMessageId", "problemIds", "messages", "problems", "systems", "accessContext",
            "existingWorkItems", "workRecord", "expiresAt", "limits");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private ApplicationEvents events;

    private final UUID departmentId = UUID.randomUUID();
    private final UUID memberId = UUID.randomUUID();
    private final UUID otherMemberId = UUID.randomUUID();
    private final UUID systemId = UUID.randomUUID();
    private final UUID featureId = UUID.randomUUID();

    @BeforeEach
    void createFixtures() {
        String suffix = departmentId.toString().substring(0, 8);
        jdbc.update("INSERT INTO department (id, code, name) VALUES (?, ?, '문의 생성 테스트 부서')", departmentId, "INQ_" + suffix);
        for (UUID id : List.of(memberId, otherMemberId)) {
            jdbc.update("INSERT INTO member (id, login_id, employee_no, password_hash, name, department_id) "
                    + "VALUES (?, ?, ?, 'unused-test-hash', '문의 생성 테스트 회원', ?)", id, id.toString(), id.toString(), departmentId);
        }
        jdbc.update("INSERT INTO it_system (id, code, name, primary_owner_member_id) VALUES (?, 'COMMON_LOGIN', '테스트 로그인', ?)",
                systemId, otherMemberId);
        jdbc.update("INSERT INTO system_feature (id, it_system_id, code, name) VALUES (?, ?, ?, '문서 조회')",
                featureId, systemId, "TEST_FEATURE_" + suffix);
    }

    @AfterEach
    void cleanup() {
        String mine = "SELECT id FROM inquiry WHERE requester_member_id IN (?, ?)";
        jdbc.update("UPDATE inquiry SET current_run_id = NULL WHERE requester_member_id IN (?, ?)", memberId, otherMemberId);
        jdbc.update("DELETE FROM agent_run WHERE inquiry_id IN (" + mine + ")", memberId, otherMemberId);
        jdbc.update("DELETE FROM message WHERE inquiry_id IN (" + mine + ")", memberId, otherMemberId);
        jdbc.update("DELETE FROM inquiry WHERE requester_member_id IN (?, ?)", memberId, otherMemberId);
        jdbc.update("DELETE FROM idempotency_key WHERE actor_member_id IN (?, ?)", memberId, otherMemberId);
        jdbc.update("DELETE FROM system_feature WHERE id = ?", featureId);
        jdbc.update("DELETE FROM it_system WHERE id = ?", systemId);
        jdbc.update("DELETE FROM member WHERE id IN (?, ?)", memberId, otherMemberId);
        jdbc.update("DELETE FROM department WHERE id = ?", departmentId);
    }

    @Test
    void 문의_첫_메시지_대기_실행을_한_번에_저장하고_200_영수증을_돌려준다() throws Exception {
        UUID requestId = UUID.randomUUID();
        String content = "  결재 문서가\n안 열려요  "; // 원문은 trim하지 않는다

        create(memberId, requestId, content, systemId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(requestId.toString()))
                .andExpect(jsonPath("$.operationId").isString())
                .andExpect(jsonPath("$.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.resource.type").value("INQUIRY"))
                .andExpect(jsonPath("$.error").isEmpty());

        Map<String, Object> inquiry = jdbc.queryForMap("SELECT * FROM inquiry WHERE requester_member_id = ?", memberId);
        UUID inquiryId = (UUID) inquiry.get("id");
        assertThat((String) inquiry.get("inquiry_number")).matches("Q-\\d{8}");
        assertThat(inquiry.get("title")).isEqualTo("결재 문서가 안 열려요");
        assertThat(inquiry.get("inquiry_status")).isEqualTo("IN_PROGRESS");
        assertThat(inquiry.get("version")).isEqualTo(1);

        Map<String, Object> message = jdbc.queryForMap("SELECT * FROM message WHERE inquiry_id = ?", inquiryId);
        assertThat(message.get("sequence_no")).isEqualTo(1);
        assertThat(message.get("sender_type")).isEqualTo("USER");
        assertThat(message.get("message_status")).isEqualTo("COMPLETED");
        assertThat(message.get("content")).isEqualTo(content);
        JsonNode payload = jsonMapper.readTree(message.get("payload").toString());
        assertThat(payload.path("requestId").asString()).isEqualTo(requestId.toString());
        assertThat(payload.path("reportedSystemId").asString()).isEqualTo(systemId.toString());
        assertThat(payload.path("answerRunId").isNull()).isTrue();
        assertThat(payload.path("pendingTarget").asBoolean(true)).isFalse();

        Map<String, Object> run = jdbc.queryForMap("SELECT * FROM agent_run WHERE inquiry_id = ?", inquiryId);
        UUID runId = (UUID) run.get("id");
        assertThat(inquiry.get("current_run_id")).isEqualTo(runId);
        assertThat(run.get("task")).isEqualTo("INQUIRY_ANALYSIS");
        assertThat(run.get("run_status")).isEqualTo("QUEUED");
        assertThat(run.get("started_at")).isNull();
        assertThat(run.get("input_version")).isEqualTo(1);
        assertThat(run.get("trigger_message_id")).isEqualTo(message.get("id"));

        // AI 서버는 계약에 없는 필드를 거부하므로 키 집합까지 맞춘다
        JsonNode input = jsonMapper.readTree(run.get("input_snapshot").toString());
        Set<String> fields = new HashSet<>(input.propertyNames());
        assertThat(fields).isEqualTo(AI_RUN_REQUEST_FIELDS);
        assertThat(input.path("contractVersion").asString()).isEqualTo("hops-system-1");
        assertThat(input.path("runId").asString()).isEqualTo(runId.toString());
        assertThat(input.path("kind").asString()).isEqualTo("INQUIRY_ANALYSIS");
        assertThat(input.path("inquiryId").asString()).isEqualTo(inquiryId.toString());
        assertThat(input.path("inquiryVersion").asInt()).isEqualTo(1);
        assertThat(UUID.fromString(input.path("answerMessageId").asString())).isNotNull();
        assertThat(input.path("problemIds").isEmpty()).isTrue();
        assertThat(input.path("problems").isEmpty()).isTrue();
        assertThat(input.path("existingWorkItems").isEmpty()).isTrue();
        assertThat(input.path("workRecord").isNull()).isTrue();
        assertThat(input.path("expiresAt").asString()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,6})?Z");
        assertThat(input.path("limits").path("maxModelCalls").asInt()).isEqualTo(8);
        assertThat(input.path("limits").path("maxRetries").asInt()).isEqualTo(2);

        JsonNode first = input.path("messages").get(0);
        assertThat(input.path("messages").size()).isEqualTo(1);
        assertThat(first.path("id").asString()).isEqualTo(message.get("id").toString());
        assertThat(first.path("revision").asInt()).isEqualTo(1);
        assertThat(first.path("sequenceNo").asInt()).isEqualTo(1);
        assertThat(first.path("sender").asString()).isEqualTo("USER");
        assertThat(first.path("content").asString()).isEqualTo(content);
        assertThat(first.path("problemIds").isEmpty()).isTrue();

        JsonNode access = input.path("accessContext");
        assertThat(access.path("actorId").asString()).isEqualTo(memberId.toString());
        assertThat(access.path("purpose").asString()).isEqualTo("EMPLOYEE_HELP");
        assertThat(access.path("allowedDocumentScopes").get(0).asString()).isEqualTo("EMPLOYEE");
        assertThat(access.path("policyRevision").asInt()).isEqualTo(1);
        assertThat(access.path("systemIds").toString()).contains(systemId.toString());
        JsonNode system = null;
        for (JsonNode candidate : input.path("systems")) {
            if (candidate.path("id").asString().equals(systemId.toString())) {
                system = candidate;
            }
        }
        assertThat(system).isNotNull();
        assertThat(system.path("features").get(0).path("id").asString()).isEqualTo(featureId.toString());

        assertThat(events.stream(AgentRunQueued.class)).containsExactly(new AgentRunQueued(runId));
    }

    @Test
    void 문의_상세와_실행_조회가_대기_실행을_같은_값으로_보여준다() throws Exception {
        UUID requestId = UUID.randomUUID();
        create(memberId, requestId, "결재 문서가 안 열려요", null).andExpect(status().isOk());
        Map<String, Object> run = jdbc.queryForMap(
                "SELECT r.id, r.inquiry_id, r.input_snapshot ->> 'answerMessageId' AS answer_message_id "
                        + "FROM agent_run r JOIN inquiry i ON i.id = r.inquiry_id WHERE i.requester_member_id = ?", memberId);
        String runId = run.get("id").toString();
        String inquiryId = run.get("inquiry_id").toString();
        String answerMessageId = run.get("answer_message_id").toString();

        mockMvc.perform(as(memberId, get(PATH + "/" + inquiryId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inquiryVersion").value(1))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.currentRun.id").value(runId))
                .andExpect(jsonPath("$.currentRun.inquiryId").value(inquiryId))
                .andExpect(jsonPath("$.currentRun.inquiryVersion").value(1))
                .andExpect(jsonPath("$.currentRun.answerMessageId").value(answerMessageId))
                .andExpect(jsonPath("$.currentRun.status").value("QUEUED"))
                .andExpect(jsonPath("$.currentRun.error").isEmpty());

        mockMvc.perform(as(memberId, get("/api/v2/runs/" + runId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(runId))
                .andExpect(jsonPath("$.answerMessageId").value(answerMessageId))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.error").hasJsonPath())
                .andExpect(jsonPath("$.error").isEmpty());

        // 예약한 AI 답변 ID는 저장 전이라 대화에 나오지 않는다
        mockMvc.perform(as(memberId, get(PATH + "/" + inquiryId + "/messages")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].requestId").value(requestId.toString()));
    }

    @Test
    void 실패한_실행은_200으로_공개_오류와_접수_요청_ID를_돌려준다() throws Exception {
        UUID requestId = UUID.randomUUID();
        create(memberId, requestId, "결재 문서가 안 열려요", null).andExpect(status().isOk());
        String runId = jdbc.queryForObject("SELECT current_run_id FROM inquiry WHERE requester_member_id = ?",
                UUID.class, memberId).toString();

        finishRun(runId, "FAILED", "INVALID_OUTPUT");
        mockMvc.perform(as(memberId, get("/api/v2/runs/" + runId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error.code").value("AI_INVALID_OUTPUT"))
                .andExpect(jsonPath("$.error.requestId").value(requestId.toString()))
                .andExpect(jsonPath("$.error.retryable").value(true))
                .andExpect(jsonPath("$.error.fieldErrors").isEmpty());

        finishRun(runId, "FAILED", "SOMETHING_NEW");
        mockMvc.perform(as(memberId, get("/api/v2/runs/" + runId)))
                .andExpect(jsonPath("$.error.code").value("AI_RUN_STATE_UNKNOWN"));

        finishRun(runId, "FAILED", "AUTH_CONTEXT_INVALID");
        mockMvc.perform(as(memberId, get("/api/v2/runs/" + runId)))
                .andExpect(jsonPath("$.error.code").value("AUTH_CONTEXT_CHANGED"))
                .andExpect(jsonPath("$.error.retryable").value(false));

        finishRun(runId, "TIMED_OUT", null);
        mockMvc.perform(as(memberId, get("/api/v2/runs/" + runId)))
                .andExpect(jsonPath("$.status").value("TIMED_OUT"))
                .andExpect(jsonPath("$.error.code").value("DEADLINE_EXCEEDED"));

        // DB CANCELLED는 밖으로 SUPERSEDED이고 오류가 없다
        finishRun(runId, "CANCELLED", null);
        mockMvc.perform(as(memberId, get("/api/v2/runs/" + runId)))
                .andExpect(jsonPath("$.status").value("SUPERSEDED"))
                .andExpect(jsonPath("$.error").isEmpty());
    }

    @Test
    void 같은_요청_키는_처음_결과를_돌려주고_다른_본문이면_409다() throws Exception {
        UUID requestId = UUID.randomUUID();
        String operationId = jsonMapper.readTree(create(memberId, requestId, "결재 문서가 안 열려요", null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("operationId").asString();

        create(memberId, requestId, "결재 문서가 안 열려요", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operationId").value(operationId));
        create(memberId, requestId, "다른 내용", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(count("SELECT count(*) FROM inquiry WHERE requester_member_id = ?")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM agent_run r JOIN inquiry i ON i.id = r.inquiry_id "
                + "WHERE i.requester_member_id = ?")).isEqualTo(1);
    }

    @Test
    void 잘못된_입력은_400이고_아무것도_저장하지_않는다() throws Exception {
        // 공백만(전각 공백·줄바꿈 포함), 4,001자, PostgreSQL TEXT에 저장할 수 없는 NUL
        for (String content : List.of("　 \n\t﻿", "가".repeat(4001), "a\u0000b")) {
            create(memberId, UUID.randomUUID(), content, null)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("content"))
                    .andExpect(jsonPath("$.fieldErrors[0].code").value("UserContent"));
        }
        create(memberId, UUID.randomUUID(), "결재 문서가 안 열려요", UUID.randomUUID())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        // 헤더 키와 본문 requestId가 다르다
        mockMvc.perform(as(memberId, post(PATH))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID(), "결재 문서가 안 열려요", null)))
                .andExpect(status().isBadRequest());

        assertThat(count("SELECT count(*) FROM inquiry WHERE requester_member_id = ?")).isZero();
        assertThat(count("SELECT count(*) FROM idempotency_key WHERE actor_member_id = ?")).isZero();
    }

    @Test
    void 남의_실행과_없는_실행은_404다() throws Exception {
        create(memberId, UUID.randomUUID(), "결재 문서가 안 열려요", null).andExpect(status().isOk());
        UUID runId = jdbc.queryForObject("SELECT current_run_id FROM inquiry WHERE requester_member_id = ?",
                UUID.class, memberId);

        for (UUID target : List.of(runId, UUID.randomUUID())) {
            mockMvc.perform(as(otherMemberId, get("/api/v2/runs/" + target)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
    }

    @Test
    void 실패한_현재_실행을_다시_분석하면_새_대기_실행을_접수한다() throws Exception {
        create(memberId, UUID.randomUUID(), "결재 문서가 안 열려요", null).andExpect(status().isOk());
        String failedId = currentRunId();
        finishRun(failedId, "FAILED", "MODEL_UNAVAILABLE");
        UUID retryRequestId = UUID.randomUUID();

        String receipt = retry(memberId, failedId, retryRequestId, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.resource.type").value("RUN"))
                .andReturn().getResponse().getContentAsString();
        String nextId = jsonMapper.readTree(receipt).path("resource").path("id").asString();

        assertThat(nextId).isNotEqualTo(failedId).isEqualTo(currentRunId());
        assertThat(jdbc.queryForObject("SELECT run_status FROM agent_run WHERE id = ?::uuid", String.class, failedId))
                .isEqualTo("FAILED"); // 실패한 실행은 되돌리지 않는다
        Map<String, Object> next = jdbc.queryForMap("SELECT * FROM agent_run WHERE id = ?::uuid", nextId);
        Map<String, Object> failed = jdbc.queryForMap("SELECT * FROM agent_run WHERE id = ?::uuid", failedId);
        assertThat(next.get("run_status")).isEqualTo("QUEUED");
        assertThat(next.get("input_version")).isEqualTo(1);
        assertThat(next.get("trigger_message_id")).isEqualTo(failed.get("trigger_message_id"));
        JsonNode nextInput = jsonMapper.readTree(next.get("input_snapshot").toString());
        JsonNode failedInput = jsonMapper.readTree(failed.get("input_snapshot").toString());
        assertThat(new HashSet<>(nextInput.propertyNames())).isEqualTo(AI_RUN_REQUEST_FIELDS);
        assertThat(nextInput.path("runId").asString()).isEqualTo(nextId);
        assertThat(nextInput.path("answerMessageId").asString()).isNotEqualTo(failedInput.path("answerMessageId").asString());
        assertThat(nextInput.path("messages")).isEqualTo(failedInput.path("messages"));
        assertThat(events.stream(AgentRunQueued.class)).contains(new AgentRunQueued(UUID.fromString(nextId)));

        mockMvc.perform(as(memberId, get("/api/v2/runs/" + nextId)))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.answerMessageId").value(nextInput.path("answerMessageId").asString()));
        // 재분석 실행의 실패 오류에는 그 재분석 요청 ID를 넣는다
        finishRun(nextId, "TIMED_OUT", null);
        mockMvc.perform(as(memberId, get("/api/v2/runs/" + nextId)))
                .andExpect(jsonPath("$.error.code").value("DEADLINE_EXCEEDED"))
                .andExpect(jsonPath("$.error.requestId").value(retryRequestId.toString()));

        // 같은 요청 키는 처음 결과이고 새 실행을 더 만들지 않는다
        retry(memberId, failedId, retryRequestId, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource.id").value(nextId));
        assertThat(count("SELECT count(*) FROM agent_run r JOIN inquiry i ON i.id = r.inquiry_id "
                + "WHERE i.requester_member_id = ?")).isEqualTo(2);
    }

    @Test
    void 다시_분석할_수_없으면_409이고_새_실행을_만들지_않는다() throws Exception {
        create(memberId, UUID.randomUUID(), "결재 문서가 안 열려요", null).andExpect(status().isOk());
        String runId = currentRunId();

        // 아직 대기 중인 실행
        retry(memberId, runId, UUID.randomUUID(), 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RUN_NOT_RETRYABLE"));
        // 권한 문맥이 바뀐 실패는 재분석 대상이 아니다 (retryable=false)
        finishRun(runId, "FAILED", "AUTH_CONTEXT_INVALID");
        retry(memberId, runId, UUID.randomUUID(), 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RUN_NOT_RETRYABLE"));

        finishRun(runId, "FAILED", "INVALID_OUTPUT");
        retry(memberId, runId, UUID.randomUUID(), 2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INQUIRY_VERSION_CONFLICT"));
        // 남의 실행·없는 실행은 존재를 알리지 않는다
        for (String target : List.of(runId, UUID.randomUUID().toString())) {
            retry(otherMemberId, target, UUID.randomUUID(), 1)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
        // 이미 다시 분석한 실행은 현재 실행이 아니다
        retry(memberId, runId, UUID.randomUUID(), 1).andExpect(status().isOk());
        retry(memberId, runId, UUID.randomUUID(), 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RUN_NOT_RETRYABLE"));

        assertThat(count("SELECT count(*) FROM agent_run r JOIN inquiry i ON i.id = r.inquiry_id "
                + "WHERE i.requester_member_id = ?")).isEqualTo(2);
    }

    private ResultActions retry(UUID actorId, String runId, UUID requestId, int expectedInquiryVersion)
            throws Exception {
        return mockMvc.perform(as(actorId, post("/api/v2/runs/" + runId + "/retries"))
                .header("Idempotency-Key", requestId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(
                        Map.of("requestId", requestId, "expectedInquiryVersion", expectedInquiryVersion))));
    }

    private String currentRunId() {
        return jdbc.queryForObject("SELECT current_run_id FROM inquiry WHERE requester_member_id = ?",
                UUID.class, memberId).toString();
    }

    private ResultActions create(UUID actorId, UUID requestId, String content, UUID reportedSystemId) throws Exception {
        return mockMvc.perform(as(actorId, post(PATH))
                .header("Idempotency-Key", requestId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(requestId, content, reportedSystemId)));
    }

    private String body(UUID requestId, String content, UUID reportedSystemId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("content", content);
        body.put("reportedSystemId", reportedSystemId);
        return jsonMapper.writeValueAsString(body);
    }

    private void finishRun(String runId, String status, String errorCode) {
        jdbc.update("UPDATE agent_run SET run_status = ?, error_code = ?, started_at = now(), completed_at = now() "
                + "WHERE id = ?::uuid", status, errorCode, runId);
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class, memberId);
    }

    private static MockHttpServletRequestBuilder as(UUID actorId, MockHttpServletRequestBuilder request) {
        return request.with(jwt().jwt(token -> token.subject(actorId.toString())));
    }
}
