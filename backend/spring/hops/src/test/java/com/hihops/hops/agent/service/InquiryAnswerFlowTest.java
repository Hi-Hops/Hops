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
    private final UUID guideDocument = UUID.randomUUID();
    private final UUID guideEvidence = UUID.randomUUID();

    private JsonNode guideSnapshot(UUID runId, UUID inquiryId) throws Exception {
        String text = ANSWER;
        String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        jdbc.update("INSERT INTO knowledge_document(id,title,document_type,it_system_id,access_level,data_split,review_status) VALUES (?,'가상 직원 가이드','EMPLOYEE_GUIDE',?,'EMPLOYEE','RAG','APPROVED')", guideDocument, systemId);
        jdbc.update("INSERT INTO knowledge_document_version(knowledge_document_id,version_no,content,content_digest,source_metadata,review_status) VALUES (?,1,?,?,?::jsonb,'APPROVED')", guideDocument, text, digest,
                "{\"origin\":\"SYNTHETIC\",\"applicability\":\"가상 로그인 화면\",\"appliesToSystemVersion\":null}");
        var snapshot = (tools.jackson.databind.node.ObjectNode) succeeded(runId, inquiryId, 1, text);
        var result = (tools.jackson.databind.node.ObjectNode) snapshot.path("result");
        var problem = result.putArray("problemProposals").addObject().put("problemRef", "new:guide")
                .put("summary", "가상 로그인 안내").put("intent", "HOW_TO").put("processingType", "KNOWLEDGE_QA")
                .put("reportedSystemCandidateId", systemId.toString());
        problem.putArray("facts"); problem.putArray("assumptions"); problem.putArray("unknowns");
        problem.set("details", jsonMapper.valueToTree(new com.hihops.hops.ticket.dto.StandardRequestResponse.RequestDetailsResponse(
                "로그인 화면", null, null, null, null, null, null, null, java.util.List.of())));
        result.putObject("guide").put("problemRef", "new:guide").put("messageText", text)
                .putArray("evidenceIds").add(guideEvidence.toString());
        result.putArray("evidence").add(jsonMapper.valueToTree(new com.hihops.hops.ticket.dto.StandardRequestResponse.KnowledgeEvidenceResponse(
                guideEvidence, guideDocument, 1, "GUIDE", "가상 직원 가이드", text, systemId, null, "가상 로그인 화면", "EMPLOYEE", "SYNTHETIC")));
        return snapshot;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"RESOLVED", "UNRESOLVED"})
    void AI_안내_저장부터_GUIDE_확인요청_조회와_문의자_응답까지_연결한다(String decision) throws Exception {
        UUID inquiryId = createInquiry("로그인 화면 사용법을 알려 주세요");
        UUID runId = runOf(inquiryId);
        when(client.snapshot(runId.toString())).thenReturn(guideSnapshot(runId, inquiryId));
        dispatcher.deliver(runId);
        dispatcher.deliver(runId);
        var response = mockMvc.perform(as(get("/api/v2/inquiries/" + inquiryId))).andExpect(status().isOk()).andReturn();
        var problem = jsonMapper.readTree(response.getResponse().getContentAsString()).path("problems").get(0);
        assertThat(problem.path("state").asString()).isEqualTo("WAITING_CONFIRMATION");
        assertThat(problem.path("guide").path("evidence").size()).isEqualTo(1);
        var request = problem.path("activeResolutionRequest");
        assertThat(request.path("source").asString()).isEqualTo("GUIDE");
        assertThat(request.path("sourceId").asString()).isEqualTo(answerMessageIdOf(runId).toString());
        assertThat(request.path("state").asString()).isEqualTo("PENDING");
        String requestPath = "/api/v2/resolution-requests/" + request.path("id").asString();
        mockMvc.perform(as(get(requestPath))).andExpect(status().isOk()).andExpect(jsonPath("$.sourceVersion").value(1));
        mockMvc.perform(as(ownerId, get(requestPath))).andExpect(status().isNotFound());
        UUID commandId = UUID.randomUUID();
        String command = jsonMapper.writeValueAsString(Map.of("requestId", commandId, "expectedRequestRevision", 1, "decision", decision));
        for (int replay = 0; replay < 2; replay++) mockMvc.perform(as(post(requestPath + "/responses"))
                .header("Idempotency-Key", commandId).contentType(MediaType.APPLICATION_JSON).content(command)).andExpect(status().isOk());
        mockMvc.perform(as(get(requestPath))).andExpect(jsonPath("$.state").value(decision)).andExpect(jsonPath("$.requestRevision").value(2));
        var latest = mockMvc.perform(as(get("/api/v2/inquiries/" + inquiryId))).andExpect(status().isOk()).andReturn();
        var latestProblem = jsonMapper.readTree(latest.getResponse().getContentAsString()).path("problems").get(0);
        assertThat(latestProblem.path("id")).isEqualTo(problem.path("id"));
        assertThat(latestProblem.path("questionCount").asInt()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket WHERE inquiry_id=?", Integer.class, inquiryId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run WHERE inquiry_id=? AND task='CURATION'", Integer.class, inquiryId)).isZero();
        if ("RESOLVED".equals(decision)) assertThat(latestProblem.path("state").asString()).isEqualTo("RESOLVED");
        else {
            var next = jsonMapper.readTree(jdbc.queryForObject("SELECT input_snapshot::text FROM agent_run WHERE id=(SELECT current_run_id FROM inquiry WHERE id=?)", String.class, inquiryId));
            assertThat(next.path("inquiryVersion").asInt()).isEqualTo(1);
            assertThat(next.path("problemIds").get(0)).isEqualTo(problem.path("id"));
            // AI가 미해결 응답을 알아보고 이미 안내한 근거를 뺄 수 있도록 입력에 실린다 (docs/ai/AGENT_ARCHITECTURE.md 5.3절 (c))
            var reanalysis = next.path("problems").get(0);
            assertThat(reanalysis.path("state").asString()).isEqualTo("ANALYZING");
            assertThat(reanalysis.path("resolutionContext").path("state").asString()).isEqualTo("UNRESOLVED");
            assertThat(reanalysis.path("resolutionContext").path("source").asString()).isEqualTo("GUIDE");
            assertThat(reanalysis.path("resolutionContext").path("sourceId").asString())
                    .isEqualTo(answerMessageIdOf(runId).toString());
            assertThat(reanalysis.path("standardRequest").path("knowledge").size()).isEqualTo(1);
            assertThat(reanalysis.path("standardRequest").path("knowledge").get(0).path("documentId").asString())
                    .isEqualTo(guideDocument.toString());
            assertThat(reanalysis.path("askedFields").isArray()).isTrue();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"withdrawn", "uncited", "unknown-problem", "unsupported-system", "staff", "null-details", "conflicting-ticket"})
    void 잘못된_안내는_답변과_GUIDE_요청을_함께_저장하지_않는다(String invalid) throws Exception {
        UUID inquiryId = createInquiry("로그인 방법을 알려 주세요");
        UUID runId = runOf(inquiryId);
        var snapshot = guideSnapshot(runId, inquiryId);
        var result = (tools.jackson.databind.node.ObjectNode) snapshot.path("result");
        var guide = (tools.jackson.databind.node.ObjectNode) result.path("guide");
        switch (invalid) {
            case "withdrawn" -> jdbc.update("UPDATE knowledge_document SET review_status='PENDING' WHERE id=?", guideDocument);
            case "uncited" -> guide.putArray("evidenceIds");
            case "unknown-problem" -> guide.put("problemRef", "new:absent");
            case "unsupported-system" -> ((tools.jackson.databind.node.ObjectNode) result.path("problemProposals").get(0))
                    .put("reportedSystemCandidateId", UUID.randomUUID().toString());
            case "staff" -> jdbc.update("UPDATE knowledge_document SET access_level='STAFF' WHERE id=?", guideDocument);
            case "null-details" -> ((tools.jackson.databind.node.ObjectNode) result.path("problemProposals").get(0)).putNull("details");
            case "conflicting-ticket" -> result.putArray("ticketProposals").addObject().put("problemRef", "new:guide");
            default -> throw new IllegalArgumentException(invalid);
        }
        when(client.snapshot(runId.toString())).thenReturn(snapshot);
        dispatcher.deliver(runId);
        assertThat(jdbc.queryForObject("SELECT run_status FROM agent_run WHERE id=?", String.class, runId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message WHERE inquiry_id=?", Integer.class, inquiryId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inquiry_problem WHERE inquiry_id=?", Integer.class, inquiryId)).isZero();
    }

    @Test
    void 새_안내가_생기면_이전_PENDING_요청으로_현재_문제를_완료할_수_없다() throws Exception {
        UUID inquiryId = createInquiry("로그인 사용법을 알려 주세요");
        UUID firstRun = runOf(inquiryId);
        var firstSnapshot = guideSnapshot(firstRun, inquiryId);
        when(client.snapshot(firstRun.toString())).thenReturn(firstSnapshot);
        dispatcher.deliver(firstRun);
        var first = mockMvc.perform(as(get("/api/v2/inquiries/" + inquiryId))).andExpect(status().isOk()).andReturn();
        var problem = jsonMapper.readTree(first.getResponse().getContentAsString()).path("problems").get(0);
        String oldRequest = problem.path("activeResolutionRequest").path("id").asString();
        UUID sendId = UUID.randomUUID();
        mockMvc.perform(as(post("/api/v2/inquiries/" + inquiryId + "/messages")).header("Idempotency-Key", sendId)
                .contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(Map.of(
                        "requestId", sendId, "expectedInquiryVersion", 1, "content", "같은 화면 안내를 다시 확인해 주세요",
                        "problemIds", List.of(problem.path("id").asString()))))).andExpect(status().isOk());
        UUID nextRun = jdbc.queryForObject("SELECT current_run_id FROM inquiry WHERE id=?", UUID.class, inquiryId);
        var next = (tools.jackson.databind.node.ObjectNode) firstSnapshot.deepCopy();
        next.put("runId", nextRun.toString());
        var result = (tools.jackson.databind.node.ObjectNode) next.path("result");
        result.put("runId", nextRun.toString()).put("inquiryVersion", 2);
        ((tools.jackson.databind.node.ObjectNode) result.path("guide")).put("problemRef", problem.path("id").asString());
        ((tools.jackson.databind.node.ObjectNode) result.path("problemProposals").get(0)).put("problemRef", problem.path("id").asString());
        when(client.snapshot(nextRun.toString())).thenReturn(next);
        dispatcher.deliver(nextRun);
        var current = mockMvc.perform(as(get("/api/v2/inquiries/" + inquiryId))).andExpect(status().isOk()).andReturn();
        var request = jsonMapper.readTree(current.getResponse().getContentAsString()).path("problems").get(0).path("activeResolutionRequest");
        assertThat(request.path("id").asString()).isNotEqualTo(oldRequest);
        UUID responseId = UUID.randomUUID();
        mockMvc.perform(as(post("/api/v2/resolution-requests/" + oldRequest + "/responses"))
                .header("Idempotency-Key", responseId).contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("requestId", responseId, "expectedRequestRevision", 1, "decision", "RESOLVED"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESOLUTION_REVISION_CONFLICT"));
    }

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
        jdbc.update("DELETE FROM standard_request WHERE inquiry_problem_id IN (SELECT id FROM inquiry_problem WHERE inquiry_id IN (" + mine + "))", memberId, ownerId);
        jdbc.update("DELETE FROM inquiry_problem WHERE inquiry_id IN (" + mine + ")", memberId, ownerId);
        jdbc.update("DELETE FROM agent_run WHERE inquiry_id IN (" + mine + ")", memberId, ownerId);
        jdbc.update("DELETE FROM message WHERE inquiry_id IN (" + mine + ")", memberId, ownerId);
        jdbc.update("DELETE FROM inquiry WHERE requester_member_id IN (?, ?)", memberId, ownerId);
        jdbc.update("DELETE FROM idempotency_key WHERE actor_member_id IN (?, ?)", memberId, ownerId);
        jdbc.update("DELETE FROM knowledge_document_version WHERE knowledge_document_id=?", guideDocument);
        jdbc.update("DELETE FROM knowledge_document WHERE id=?", guideDocument);
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
        // 결과 차수는 티켓 생성 결과용이라 AI 답변만 저장해서는 늘지 않는다 (DATA_TYPES InquiryResult)
        assertThat(jdbc.queryForObject("SELECT result_revision FROM inquiry WHERE id = ?", Integer.class, inquiryId))
                .isEqualTo(1);

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
        return as(memberId, request);
    }

    private MockHttpServletRequestBuilder as(UUID actorId, MockHttpServletRequestBuilder request) {
        return request.with(jwt().jwt(token -> token.subject(actorId.toString())
                .claim("departmentId", departmentId.toString()).claim("roles", List.of()).claim("policyRevision", 1)
                .claim("primarySystemIds", actorId.equals(ownerId) ? List.of(systemId.toString()) : List.of())
                .expiresAt(java.time.Instant.now().plusSeconds(600))));
    }
}
