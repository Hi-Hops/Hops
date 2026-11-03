package com.hihops.hops.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.hihops.hops.agent.client.AgentRunClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 문의 생성 → AI 전달 → 결과 반영의 업무 쪽 흐름을 실제 테스트 DB로 확인한다. AI 서버 대신 AgentRunClient만 가짜로 두고,
 * 나머지(문의 생성 API, dispatcher, 상담 결과 처리기, 메시지·실행 조회 API)는 실제 코드를 쓴다.
 *
 * <p>테스트 프로필은 자동 전달을 꺼 두므로 접수 뒤 {@link AgentRunDispatcher#deliver}를 직접 불러 전달 단계를 진행시킨다.
 * 문의 생성은 멱등 처리 때문에 실제로 commit하므로 {@link #cleanup()}에서 넣은 행을 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"db", "test"})
class InquiryAnswerFlowTest {

    private static final String ANSWER = "전자결재의 문서 조회에서 작성 기간을 먼저 확인해 보세요.";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private AgentRunDispatcher dispatcher;

    @MockitoBean
    private AgentRunClient client;

    private final UUID departmentId = UUID.randomUUID();
    private final UUID memberId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();
    private final UUID systemId = UUID.randomUUID();

    @BeforeEach
    void createFixtures() {
        String suffix = departmentId.toString().substring(0, 8);
        jdbc.update("INSERT INTO department (id, code, name) VALUES (?, ?, '답변 반영 테스트 부서')", departmentId, "ANS_" + suffix);
        for (UUID id : List.of(memberId, ownerId)) {
            jdbc.update("INSERT INTO member (id, login_id, employee_no, password_hash, name, department_id) "
                    + "VALUES (?, ?, ?, 'unused-test-hash', '답변 반영 테스트 회원', ?)", id, id.toString(), id.toString(), departmentId);
        }
        jdbc.update("INSERT INTO it_system (id, code, name, primary_owner_member_id) VALUES (?, 'COMMON_LOGIN', '테스트 로그인', ?)",
                systemId, ownerId);
    }

    @AfterEach
    void cleanup() {
        String mine = "SELECT id FROM inquiry WHERE requester_member_id IN (?, ?)";
        jdbc.update("UPDATE inquiry SET current_run_id = NULL WHERE requester_member_id IN (?, ?)", memberId, ownerId);
        jdbc.update("DELETE FROM agent_run WHERE inquiry_id IN (" + mine + ")", memberId, ownerId);
        jdbc.update("DELETE FROM message WHERE inquiry_id IN (" + mine + ")", memberId, ownerId);
        jdbc.update("DELETE FROM inquiry WHERE requester_member_id IN (?, ?)", memberId, ownerId);
        jdbc.update("DELETE FROM idempotency_key WHERE actor_member_id IN (?, ?)", memberId, ownerId);
        jdbc.update("DELETE FROM it_system WHERE id = ?", systemId);
        jdbc.update("DELETE FROM member WHERE id IN (?, ?)", memberId, ownerId);
        jdbc.update("DELETE FROM department WHERE id = ?", departmentId);
    }

    @Test
    void 접수한_문의에_AI_답변이_예약한_ID로_저장되고_실행이_성공으로_끝난다() throws Exception {
        UUID inquiryId = createInquiry("결재 문서가 안 열려요");
        UUID runId = runOf(inquiryId);
        UUID answerMessageId = answerMessageIdOf(runId);
        when(client.snapshot(runId.toString())).thenReturn(succeeded(runId, inquiryId, 1, ANSWER));

        dispatcher.deliver(runId);

        verify(client).submit(any());
        Map<String, Object> run = jdbc.queryForMap("SELECT * FROM agent_run WHERE id = ?", runId);
        assertThat(run.get("run_status")).isEqualTo("SUCCEEDED");
        assertThat(run.get("started_at")).isNotNull();
        assertThat(run.get("completed_at")).isNotNull();
        assertThat(run.get("error_code")).isNull();
        assertThat(jsonMapper.readTree(run.get("result").toString()).path("answer").asString()).isEqualTo(ANSWER);
        assertThat(jsonMapper.readTree(run.get("validation_result").toString()).path("applied").get(0).asString())
                .isEqualTo("answer");

        Map<String, Object> answer = jdbc.queryForMap("SELECT * FROM message WHERE id = ?", answerMessageId);
        assertThat(answer.get("inquiry_id")).isEqualTo(inquiryId);
        assertThat(answer.get("sender_type")).isEqualTo("AI");
        assertThat(answer.get("sequence_no")).isEqualTo(2);
        assertThat(answer.get("message_status")).isEqualTo("COMPLETED");
        assertThat(answer.get("content")).isEqualTo(ANSWER);
        JsonNode payload = jsonMapper.readTree(answer.get("payload").toString());
        assertThat(payload.path("answerRunId").asString()).isEqualTo(runId.toString());
        assertThat(payload.path("pendingTarget").asBoolean(true)).isFalse();
        assertThat(jdbc.queryForObject("SELECT result_revision FROM inquiry WHERE id = ?", Integer.class, inquiryId))
                .isEqualTo(2);

        // 화면이 쓰는 조회 API로도 보인다
        mockMvc.perform(as(get("/api/v2/runs/" + runId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.answerMessageId").value(answerMessageId.toString()));
        mockMvc.perform(as(get("/api/v2/inquiries/" + inquiryId + "/messages?size=30")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[1].id").value(answerMessageId.toString()))
                .andExpect(jsonPath("$.items[1].sender").value("AI"))
                .andExpect(jsonPath("$.items[1].content").value(ANSWER));
    }

    @Test
    void 이미_처리한_실행의_이벤트가_다시_와도_답변을_중복_저장하지_않는다() throws Exception {
        UUID inquiryId = createInquiry("결재 문서가 안 열려요");
        UUID runId = runOf(inquiryId);
        when(client.snapshot(runId.toString())).thenReturn(succeeded(runId, inquiryId, 1, ANSWER));

        dispatcher.deliver(runId);
        dispatcher.deliver(runId);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM message WHERE inquiry_id = ?", Long.class, inquiryId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT result_revision FROM inquiry WHERE id = ?", Integer.class, inquiryId))
                .isEqualTo(2);
    }

    @Test
    void 접수_뒤_권한_세대가_바뀌면_AI에_보내지_않고_실패로_끝낸다() throws Exception {
        UUID inquiryId = createInquiry("결재 문서가 안 열려요");
        UUID runId = runOf(inquiryId);
        jdbc.update("UPDATE member SET policy_revision = policy_revision + 1 WHERE id = ?", memberId);

        dispatcher.deliver(runId);

        verify(client, never()).submit(any());
        Map<String, Object> run = jdbc.queryForMap("SELECT run_status, error_code FROM agent_run WHERE id = ?", runId);
        assertThat(run.get("run_status")).isEqualTo("FAILED");
        assertThat(run.get("error_code")).isEqualTo("AUTH_CONTEXT_INVALID");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message WHERE inquiry_id = ?", Long.class, inquiryId)).isEqualTo(1);
        mockMvc.perform(as(get("/api/v2/runs/" + runId)))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error.code").value("AUTH_CONTEXT_CHANGED"));
    }

    @Test
    void AI가_답하는_동안_권한_세대가_바뀌면_답변을_저장하지_않고_권한_오류로_끝낸다() throws Exception {
        UUID inquiryId = createInquiry("결재 문서가 안 열려요");
        UUID runId = runOf(inquiryId);
        // 접수 전달(권한 재확인)은 통과하고, AI 결과를 기다리는 사이에 문의자의 권한 세대가 바뀐다
        when(client.snapshot(runId.toString())).thenAnswer(invocation -> {
            jdbc.update("UPDATE member SET policy_revision = policy_revision + 1 WHERE id = ?", memberId);
            return succeeded(runId, inquiryId, 1, ANSWER);
        });

        dispatcher.deliver(runId);

        verify(client).submit(any());
        Map<String, Object> run = jdbc.queryForMap("SELECT run_status, error_code, result FROM agent_run WHERE id = ?", runId);
        assertThat(run.get("run_status")).isEqualTo("FAILED");
        assertThat(run.get("error_code")).isEqualTo("AUTH_CONTEXT_INVALID");
        assertThat(run.get("result")).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message WHERE inquiry_id = ?", Long.class, inquiryId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT result_revision FROM inquiry WHERE id = ?", Integer.class, inquiryId))
                .isEqualTo(1);
        mockMvc.perform(as(get("/api/v2/runs/" + runId)))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error.code").value("AUTH_CONTEXT_CHANGED"));
    }

    @Test
    void 더_새_입력이_생긴_문의의_늦은_결과는_답변으로_저장하지_않는다() throws Exception {
        UUID inquiryId = createInquiry("결재 문서가 안 열려요");
        UUID runId = runOf(inquiryId);
        when(client.snapshot(runId.toString())).thenReturn(succeeded(runId, inquiryId, 1, ANSWER));
        jdbc.update("UPDATE inquiry SET version = 2 WHERE id = ?", inquiryId);

        dispatcher.deliver(runId);

        Map<String, Object> run = jdbc.queryForMap("SELECT run_status, error_code, result FROM agent_run WHERE id = ?", runId);
        assertThat(run.get("run_status")).isEqualTo("CANCELLED");
        assertThat(run.get("error_code")).isEqualTo("SUPERSEDED");
        assertThat(run.get("result")).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message WHERE inquiry_id = ?", Long.class, inquiryId)).isEqualTo(1);
        mockMvc.perform(as(get("/api/v2/runs/" + runId))).andExpect(jsonPath("$.status").value("SUPERSEDED"));
    }

    @Test
    void AI가_실패로_끝내면_답변을_저장하지_않고_공개_오류로_보인다() throws Exception {
        UUID inquiryId = createInquiry("결재 문서가 안 열려요");
        UUID runId = runOf(inquiryId);
        when(client.snapshot(runId.toString())).thenReturn(jsonMapper.readTree("{\"contractVersion\":\"hops-system-1\",\"runId\":\""
                + runId + "\",\"status\":\"FAILED\",\"lastSeq\":0,\"result\":null,"
                + "\"error\":{\"code\":\"MODEL_UNAVAILABLE\",\"message\":\"x\",\"retryable\":true}}"));

        dispatcher.deliver(runId);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM message WHERE inquiry_id = ?", Long.class, inquiryId)).isEqualTo(1);
        mockMvc.perform(as(get("/api/v2/runs/" + runId)))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error.code").value("MODEL_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.retryable").value(true));
    }

    @Test
    void 결과의_답변이_비어_있으면_저장하지_않고_출력_오류로_끝낸다() throws Exception {
        UUID inquiryId = createInquiry("결재 문서가 안 열려요");
        UUID runId = runOf(inquiryId);
        when(client.snapshot(runId.toString())).thenReturn(succeeded(runId, inquiryId, 1, "   "));

        dispatcher.deliver(runId);

        assertThat(jdbc.queryForMap("SELECT run_status, error_code FROM agent_run WHERE id = ?", runId))
                .containsEntry("run_status", "FAILED").containsEntry("error_code", "INVALID_OUTPUT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message WHERE inquiry_id = ?", Long.class, inquiryId)).isEqualTo(1);
    }

    private UUID createInquiry(String content) throws Exception {
        UUID requestId = UUID.randomUUID();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("content", content);
        body.put("reportedSystemId", systemId);
        mockMvc.perform(as(post("/api/v2/inquiries")).header("Idempotency-Key", requestId.toString())
                .contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
        return jdbc.queryForObject("SELECT id FROM inquiry WHERE requester_member_id = ?", UUID.class, memberId);
    }

    private UUID runOf(UUID inquiryId) {
        return jdbc.queryForObject("SELECT id FROM agent_run WHERE inquiry_id = ?", UUID.class, inquiryId);
    }

    private UUID answerMessageIdOf(UUID runId) {
        String snapshot = jdbc.queryForObject("SELECT input_snapshot::text FROM agent_run WHERE id = ?", String.class, runId);
        return UUID.fromString(jsonMapper.readTree(snapshot).path("answerMessageId").asString());
    }

    private JsonNode succeeded(UUID runId, UUID inquiryId, int inquiryVersion, String answer) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractVersion", "hops-system-1");
        result.put("runId", runId);
        result.put("kind", "INQUIRY_ANALYSIS");
        result.put("inquiryId", inquiryId);
        result.put("inquiryVersion", inquiryVersion);
        result.put("status", "SUCCEEDED");
        result.put("answer", answer);
        result.put("problemProposals", List.of());
        result.put("questions", List.of());
        result.put("guide", null);
        result.put("ticketProposals", List.of());
        result.put("caseProposal", null);
        result.put("evidence", List.of());
        result.put("error", null);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("contractVersion", "hops-system-1");
        snapshot.put("runId", runId);
        snapshot.put("status", "SUCCEEDED");
        snapshot.put("lastSeq", 0);
        snapshot.put("result", result);
        snapshot.put("error", null);
        return jsonMapper.valueToTree(snapshot);
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request) {
        return request.with(jwt().jwt(token -> token.subject(memberId.toString())));
    }
}
