package com.hihops.hops.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import com.hihops.hops.ticket.dto.StandardRequestResponse.KnowledgeEvidenceResponse;
import com.hihops.hops.ticket.dto.StandardRequestResponse.RequestDetailsResponse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 질문·문제 제안 결과(problemProposals·questions)를 업무에 저장하고 다음 실행 입력에 돌려주는 흐름을 실제 테스트 DB로 확인한다.
 * AI 서버 대신 AgentRunClient만 가짜로 두고 나머지는 실제 코드를 쓴다 (InquiryAnswerFlowTest와 같은 방식).
 * 문의 생성은 멱등 처리 때문에 실제로 commit하므로 {@link #cleanup()}에서 넣은 행을 지운다.
 */
@SpringBootTest(properties = "SPRING_SERVICE_TOKEN=" + TicketCreationTestBodies.TOKEN)
@AutoConfigureMockMvc
@ActiveProfiles({"db", "test"})
class InquiryQuestionFlowTest {

    private static final String QUESTION = "어느 화면에서 문서가 열리지 않나요?";

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
    private final UUID document = UUID.randomUUID();
    private final UUID evidenceId = UUID.randomUUID();

    @BeforeEach
    void createFixtures() {
        String suffix = departmentId.toString().substring(0, 8);
        jdbc.update("INSERT INTO department (id, code, name) VALUES (?, ?, '질문 저장 테스트 부서')", departmentId, "QST_" + suffix);
        for (UUID id : List.of(memberId, ownerId)) {
            jdbc.update("INSERT INTO member (id, login_id, employee_no, password_hash, name, department_id) "
                    + "VALUES (?, ?, ?, 'unused-test-hash', '질문 저장 테스트 회원', ?)", id, id.toString(), id.toString(), departmentId);
        }
        jdbc.update("INSERT INTO it_system (id, code, name, primary_owner_member_id) VALUES (?, 'COMMON_LOGIN', '테스트 결재', ?)",
                systemId, ownerId);
    }

    @AfterEach
    void cleanup() {
        String mine = "SELECT id FROM inquiry WHERE requester_member_id IN (?, ?)";
        jdbc.update("UPDATE inquiry SET current_run_id = NULL WHERE requester_member_id IN (?, ?)", memberId, ownerId);
        jdbc.update("DELETE FROM ticket_assignment WHERE ticket_id IN (SELECT id FROM ticket WHERE inquiry_id IN (" + mine + "))", memberId, ownerId);
        jdbc.update("DELETE FROM ticket WHERE inquiry_id IN (" + mine + ")", memberId, ownerId);
        jdbc.update("DELETE FROM agent_ticket_request WHERE run_id IN (SELECT id FROM agent_run WHERE inquiry_id IN (" + mine + "))", memberId, ownerId);
        jdbc.update("DELETE FROM standard_request WHERE inquiry_problem_id IN (SELECT id FROM inquiry_problem WHERE inquiry_id IN (" + mine + "))", memberId, ownerId);
        jdbc.update("DELETE FROM inquiry_problem WHERE inquiry_id IN (" + mine + ")", memberId, ownerId);
        jdbc.update("DELETE FROM agent_run WHERE inquiry_id IN (" + mine + ")", memberId, ownerId);
        jdbc.update("DELETE FROM message WHERE inquiry_id IN (" + mine + ")", memberId, ownerId);
        jdbc.update("DELETE FROM inquiry WHERE requester_member_id IN (?, ?)", memberId, ownerId);
        jdbc.update("DELETE FROM idempotency_key WHERE actor_member_id IN (?, ?)", memberId, ownerId);
        jdbc.update("DELETE FROM knowledge_document_version WHERE knowledge_document_id=?", document);
        jdbc.update("DELETE FROM knowledge_document WHERE id=?", document);
        jdbc.update("DELETE FROM it_system WHERE id = ?", systemId);
        jdbc.update("DELETE FROM member WHERE id IN (?, ?)", memberId, ownerId);
        jdbc.update("DELETE FROM department WHERE id = ?", departmentId);
    }

    @Test
    void 질문_결과를_반영하면_문제와_요청서와_질문_묶음이_저장되고_질문_수가_오른다() throws Exception {
        UUID inquiryId = createInquiry("결재 문서가 안 열려요");
        UUID runId = runOf(inquiryId);
        UUID userMessage = userMessageOf(inquiryId);
        when(client.snapshot(runId.toString())).thenReturn(snapshot(runId, inquiryId, 1, QUESTION,
                List.of(proposal("new:p1", "OBSERVED_SYMPTOM", "INVESTIGATION", systemId, userMessage)),
                List.of(question("new:p1", "b1", QUESTION, "taskOrScreen"))));

        dispatcher.deliver(runId);

        assertThat(jdbc.queryForObject("SELECT run_status FROM agent_run WHERE id=?", String.class, runId)).isEqualTo("SUCCEEDED");
        Map<String, Object> problem = jdbc.queryForMap("SELECT * FROM inquiry_problem WHERE inquiry_id=?", inquiryId);
        assertThat(problem).containsEntry("problem_status", "CLARIFYING").containsEntry("clarify_count", 1)
                .containsEntry("agent_run_id", runId);
        UUID problemId = (UUID) problem.get("id");
        Map<String, Object> standard = jdbc.queryForMap("SELECT * FROM standard_request WHERE inquiry_problem_id=?", problemId);
        assertThat(standard).containsEntry("intent", "OBSERVED_SYMPTOM").containsEntry("work_type", "INVESTIGATION")
                .containsEntry("inquiry_version", 1).containsEntry("agent_run_id", runId);
        assertThat(standard.get("reported_system_id")).isEqualTo(systemId);

        // AI 메시지: 질문 묶음(물은 항목 포함)과 질문 문구의 문제 연결
        JsonNode answer = jsonMapper.readTree(jdbc.queryForObject(
                "SELECT payload::text FROM message WHERE inquiry_id=? AND sender_type='AI'", String.class, inquiryId));
        assertThat(answer.path("questionBundles").size()).isEqualTo(1);
        assertThat(answer.path("questionBundles").get(0).path("problemId").asString()).isEqualTo(problemId.toString());
        assertThat(answer.path("questionBundles").get(0).path("bundleRef").asString()).isEqualTo("b1");
        assertThat(answer.path("questionBundles").get(0).path("requestedFields").get(0).asString()).isEqualTo("taskOrScreen");
        assertThat(answer.path("problemLinks").get(0).path("problemId").asString()).isEqualTo(problemId.toString());
        // 사실이 인용한 사용자 메시지는 이 문제에 연결된다(연결이 없으면 다음 입력에서 확인 사실이 걸러진다)
        JsonNode user = jsonMapper.readTree(jdbc.queryForObject("SELECT payload::text FROM message WHERE id=?", String.class, userMessage));
        assertThat(user.path("problemLinks").get(0).path("problemId").asString()).isEqualTo(problemId.toString());

        JsonNode validation = jsonMapper.readTree(jdbc.queryForObject("SELECT validation_result::text FROM agent_run WHERE id=?",
                String.class, runId));
        assertThat(validation.path("applied").toString()).contains("problemProposals", "questions");
        assertThat(validation.path("unapplied").toString()).doesNotContain("problemProposals", "questions");

        mockMvc.perform(as(get("/api/v2/inquiries/" + inquiryId))).andExpect(status().isOk())
                .andExpect(jsonPath("$.problems[0].id").value(problemId.toString()))
                .andExpect(jsonPath("$.problems[0].questionCount").value(1))
                .andExpect(jsonPath("$.problems[0].state").value("WAITING_USER"));
    }

    @Test
    void 사용자가_답하면_다음_실행_입력에_저장한_문제와_질문_횟수와_확인_사실이_돌아온다() throws Exception {
        UUID inquiryId = askOnce("결재 문서가 안 열려요");
        UUID problemId = problemOf(inquiryId);

        JsonNode next = secondRunInput(inquiryId, "결재함 화면이에요");

        assertThat(next.path("inquiryVersion").asInt()).isEqualTo(2);
        JsonNode input = next.path("problems").get(0);
        assertThat(input.path("id").asString()).isEqualTo(problemId.toString());
        assertThat(input.path("questionCount").asInt()).isEqualTo(1);
        assertThat(input.path("askedFields").size()).isEqualTo(1);
        assertThat(input.path("askedFields").get(0).asString()).isEqualTo("taskOrScreen");
        assertThat(input.path("intent").asString()).isEqualTo("OBSERVED_SYMPTOM");
        assertThat(input.path("reportedSystemId").asString()).isEqualTo(systemId.toString());
        assertThat(input.path("standardRequest").path("confirmedFacts").size()).isEqualTo(1);
        assertThat(input.path("standardRequest").path("unknowns").get(0).asString()).isEqualTo("화면");
        // 사용자 메시지에 문제 연결이 생겨 AI가 어느 문제의 메시지인지 알 수 있다
        assertThat(next.path("messages").get(0).path("problemIds").get(0).asString()).isEqualTo(problemId.toString());
    }

    @Test
    void 두번째_질문의_답만_받으면_해당_문제의_출처와_질문_이력만_다음_실행에_반영한다() throws Exception {
        UUID inquiryId = askOnce("문서함과 로그인 화면에서 각각 오류가 나요");
        UUID first = problemOf(inquiryId);
        String loginQuestion = "로그인 오류는 언제부터 발생했나요?";
        JsonNode split = secondRunInput(inquiryId, "로그인 화면에서도 접속 오류가 나요");
        UUID splitRun = UUID.fromString(split.path("runId").asString());
        when(client.snapshot(splitRun.toString())).thenReturn(snapshot(splitRun, inquiryId, 2, loginQuestion,
                List.of(proposal("new:login", "OBSERVED_SYMPTOM", "INVESTIGATION", systemId, userMessageOf(inquiryId))),
                List.of(question("new:login", "login-time", loginQuestion, "occurredAt"))));
        dispatcher.deliver(splitRun);
        UUID second = jdbc.queryForObject("SELECT id FROM inquiry_problem WHERE inquiry_id=? AND problem_no=2",
                UUID.class, inquiryId);

        String reply = "오늘 오전부터요";
        JsonNode answerInput = secondRunInput(inquiryId, reply);
        UUID answerRun = UUID.fromString(answerInput.path("runId").asString());
        JsonNode replyMessage = answerInput.path("messages").get(4);
        UUID replyId = UUID.fromString(replyMessage.path("id").asString());
        assertThat(replyMessage.path("content").asString()).isEqualTo(reply);
        assertThat(replyMessage.path("problemIds").toString()).isEqualTo(jsonMapper.writeValueAsString(List.of(second)));
        assertThat(answerInput.path("problemIds")).isEqualTo(replyMessage.path("problemIds"));
        assertThat(answerInput.path("problems").get(0).path("state").asString()).isEqualTo("WAITING_USER");
        assertThat(answerInput.path("problems").get(1).path("state").asString()).isEqualTo("ANALYZING");

        String nextQuestion = "오류 문구가 무엇인가요?";
        when(client.snapshot(answerRun.toString())).thenReturn(snapshot(answerRun, inquiryId, 3, nextQuestion,
                List.of(proposal(second.toString(), "OBSERVED_SYMPTOM", "INVESTIGATION", systemId, replyId)),
                List.of(question(second.toString(), "login-error", nextQuestion, "actualBehavior"))));
        dispatcher.deliver(answerRun);
        mockMvc.perform(as(get("/api/v2/inquiries/" + inquiryId))).andExpect(status().isOk())
                .andExpect(jsonPath("$.problems[0].id").value(first.toString()))
                .andExpect(jsonPath("$.problems[0].state").value("WAITING_USER"))
                .andExpect(jsonPath("$.problems[0].questionCount").value(1))
                .andExpect(jsonPath("$.problems[1].id").value(second.toString()))
                .andExpect(jsonPath("$.problems[1].questionCount").value(2));

        JsonNode next = secondRunInput(inquiryId, "접속 오류라고 나와요");
        assertThat(next.path("problemIds")).isEqualTo(answerInput.path("problemIds"));
        JsonNode firstInput = next.path("problems").get(0);
        JsonNode secondInput = next.path("problems").get(1);
        assertThat(firstInput.path("askedFields").toString()).isEqualTo("[\"taskOrScreen\"]");
        assertThat(firstInput.path("standardRequest").path("confirmedFacts").toString()).doesNotContain(replyId.toString());
        assertThat(secondInput.path("askedFields").toString()).isEqualTo("[\"occurredAt\",\"actualBehavior\"]");
        assertThat(secondInput.path("standardRequest").path("confirmedFacts").get(0).path("sourceMessageIds").get(0)
                .asString()).isEqualTo(replyId.toString());
    }

    @Test
    void 물은_항목은_묶음마다_모아_중복_없이_물은_순서대로_입력에_돌려준다() throws Exception {
        UUID inquiryId = createInquiry("결재 문서가 안 열려요");
        assertThat(jsonMapper.readTree(jdbc.queryForObject("SELECT input_snapshot::text FROM agent_run WHERE inquiry_id=?",
                String.class, inquiryId)).path("problems").size()).isZero();
        UUID runId = runOf(inquiryId);
        when(client.snapshot(runId.toString())).thenReturn(snapshot(runId, inquiryId, 1, QUESTION,
                List.of(proposal("new:p1", "OBSERVED_SYMPTOM", "INVESTIGATION", systemId, userMessageOf(inquiryId))),
                List.of(question("new:p1", "b1", QUESTION, "taskOrScreen"))));
        dispatcher.deliver(runId);
        UUID problemId = problemOf(inquiryId);

        JsonNode second = secondRunInput(inquiryId, "잘 모르겠어요");
        UUID secondRun = UUID.fromString(second.path("runId").asString());
        Map<String, Object> asked = question(problemId.toString(), "b2", QUESTION, "expectedBehavior");
        asked.put("requestedFields", List.of("expectedBehavior", "taskOrScreen"));
        when(client.snapshot(secondRun.toString())).thenReturn(snapshot(secondRun, inquiryId, 2, QUESTION,
                List.of(proposal(problemId.toString(), "OBSERVED_SYMPTOM", "INVESTIGATION", systemId, userMessageOf(inquiryId))),
                List.of(asked)));
        dispatcher.deliver(secondRun);

        JsonNode third = secondRunInput(inquiryId, "그것도 모르겠어요");
        assertThat(third.path("problems").get(0).path("questionCount").asInt()).isEqualTo(2);
        assertThat(third.path("problems").get(0).path("askedFields").toString())
                .isEqualTo("[\"taskOrScreen\",\"expectedBehavior\"]");
    }

    @Test
    void 사실이_인용한_직원용_근거는_요청서에_남아_다음_입력에서도_확인_사실이_유지된다() throws Exception {
        insertKnowledge();
        UUID inquiryId = createInquiry("결재 문서가 안 열려요");
        UUID runId = runOf(inquiryId);
        UUID userMessage = userMessageOf(inquiryId);
        Map<String, Object> proposal = proposal("new:p1", "OBSERVED_SYMPTOM", "INVESTIGATION", systemId, userMessage);
        ((List<Map<String, Object>>) proposal.get("facts")).add(Map.of("text", "가이드에 같은 증상이 있다",
                "sourceMessageIds", List.of(), "knowledgeEvidenceIds", List.of(evidenceId)));
        Map<String, Object> snapshot = snapshotMap(runId, inquiryId, 1, QUESTION, List.of(proposal),
                List.of(question("new:p1", "b1", QUESTION, "taskOrScreen")));
        ((List<Object>) ((Map<String, Object>) snapshot.get("result")).get("evidence")).add(evidence());
        when(client.snapshot(runId.toString())).thenReturn(jsonMapper.valueToTree(snapshot));

        dispatcher.deliver(runId);

        assertThat(jsonMapper.readTree(jdbc.queryForObject("SELECT related_knowledge::text FROM standard_request WHERE agent_run_id=?",
                String.class, runId)).size()).isEqualTo(1);
        JsonNode next = secondRunInput(inquiryId, "결재함 화면이에요");
        JsonNode body = next.path("problems").get(0).path("standardRequest");
        assertThat(body.path("knowledge").size()).isEqualTo(1);
        assertThat(body.path("confirmedFacts").size()).isEqualTo(2);
    }

    @Test
    void 같은_묶음_참조를_다시_제안해도_질문_수를_다시_세지_않는다() throws Exception {
        UUID inquiryId = askOnce("결재 문서가 안 열려요");
        UUID problemId = problemOf(inquiryId);
        JsonNode next = secondRunInput(inquiryId, "결재함 화면이에요");
        UUID secondRun = UUID.fromString(next.path("runId").asString());
        when(client.snapshot(secondRun.toString())).thenReturn(snapshot(secondRun, inquiryId, 2, QUESTION,
                List.of(proposal(problemId.toString(), "OBSERVED_SYMPTOM", "INVESTIGATION", systemId, userMessageOf(inquiryId))),
                List.of(question(problemId.toString(), "b1", QUESTION, "taskOrScreen"))));

        dispatcher.deliver(secondRun);

        assertThat(jdbc.queryForObject("SELECT run_status FROM agent_run WHERE id=?", String.class, secondRun)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT clarify_count FROM inquiry_problem WHERE id=?", Integer.class, problemId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM standard_request WHERE inquiry_problem_id=?", Integer.class, problemId))
                .isEqualTo(2);
    }

    @Test
    void 새_묶음을_제안하면_질문_수가_오르고_다섯_번째_뒤_질문은_거부한다() throws Exception {
        UUID inquiryId = askOnce("결재 문서가 안 열려요");
        UUID problemId = problemOf(inquiryId);
        for (int round = 2; round <= 5; round++) {
            askAgain(inquiryId, problemId, "b" + round, "추가 답변 " + round);
            assertThat(jdbc.queryForObject("SELECT clarify_count FROM inquiry_problem WHERE id=?", Integer.class, problemId))
                    .isEqualTo(round);
        }

        JsonNode next = secondRunInput(inquiryId, "추가 답변 6");
        UUID sixth = UUID.fromString(next.path("runId").asString());
        when(client.snapshot(sixth.toString())).thenReturn(snapshot(sixth, inquiryId, next.path("inquiryVersion").asInt(), QUESTION,
                List.of(proposal(problemId.toString(), "OBSERVED_SYMPTOM", "INVESTIGATION", systemId, userMessageOf(inquiryId))),
                List.of(question(problemId.toString(), "b6", QUESTION, "taskOrScreen"))));
        int messagesBefore = count("SELECT count(*) FROM message WHERE inquiry_id=?", inquiryId);

        dispatcher.deliver(sixth);

        assertThat(jdbc.queryForMap("SELECT run_status, error_code FROM agent_run WHERE id=?", sixth))
                .containsEntry("run_status", "FAILED").containsEntry("error_code", "INVALID_OUTPUT");
        assertThat(jdbc.queryForObject("SELECT clarify_count FROM inquiry_problem WHERE id=?", Integer.class, problemId)).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM message WHERE inquiry_id=?", inquiryId)).isEqualTo(messagesBefore);
    }

    @Test
    void 질문_없이_시스템을_모르는_문제는_시스템_확인_대기로_저장한다() throws Exception {
        UUID inquiryId = createInquiry("문서가 안 열려요");
        UUID runId = runOf(inquiryId);
        when(client.snapshot(runId.toString())).thenReturn(snapshot(runId, inquiryId, 1, "시스템을 확인해야 합니다.",
                List.of(proposal("new:p1", "OBSERVED_SYMPTOM", "INVESTIGATION", null, userMessageOf(inquiryId))), List.of()));

        dispatcher.deliver(runId);

        assertThat(jdbc.queryForMap("SELECT problem_status, clarify_count FROM inquiry_problem WHERE inquiry_id=?", inquiryId))
                .containsEntry("problem_status", "SYSTEM_PENDING").containsEntry("clarify_count", 0);
        mockMvc.perform(as(get("/api/v2/inquiries/" + inquiryId))).andExpect(jsonPath("$.problems[0].state").value("WAITING_SYSTEM"));
    }

    @Test
    void 범위_밖_제안은_범위_밖_문제로_저장한다() throws Exception {
        UUID inquiryId = createInquiry("점심 메뉴 추천해 주세요");
        UUID runId = runOf(inquiryId);
        when(client.snapshot(runId.toString())).thenReturn(snapshot(runId, inquiryId, 1, "지원 범위 밖입니다.",
                List.of(proposal("new:p1", "OUT_OF_SCOPE", "OUT_OF_SCOPE", null, userMessageOf(inquiryId))), List.of()));

        dispatcher.deliver(runId);

        assertThat(jdbc.queryForObject("SELECT problem_status FROM inquiry_problem WHERE inquiry_id=?", String.class, inquiryId))
                .isEqualTo("OUT_OF_SCOPE");
    }

    @Test
    void 질문도_없고_시스템도_정해진_제안은_저장하지_않고_미적용으로_남긴다() throws Exception {
        UUID inquiryId = createInquiry("결재 문서가 안 열려요");
        UUID runId = runOf(inquiryId);
        when(client.snapshot(runId.toString())).thenReturn(snapshot(runId, inquiryId, 1, "확인 중입니다.",
                List.of(proposal("new:p1", "OBSERVED_SYMPTOM", "INVESTIGATION", systemId, userMessageOf(inquiryId))), List.of()));

        dispatcher.deliver(runId);

        assertThat(jdbc.queryForObject("SELECT run_status FROM agent_run WHERE id=?", String.class, runId)).isEqualTo("SUCCEEDED");
        assertThat(count("SELECT count(*) FROM inquiry_problem WHERE inquiry_id=?", inquiryId)).isZero();
        JsonNode validation = jsonMapper.readTree(jdbc.queryForObject("SELECT validation_result::text FROM agent_run WHERE id=?",
                String.class, runId));
        assertThat(validation.path("unapplied").size()).isEqualTo(1);
        assertThat(validation.path("unapplied").get(0).asString()).isEqualTo("problemProposals");
    }

    @Test
    void 티켓_도구가_이미_만든_문제는_다시_저장하지_않는다() throws Exception {
        UUID inquiryId = createInquiry("결재 문서가 안 열려요");
        UUID runId = runOf(inquiryId);
        UUID ticketProblem = UUID.randomUUID();
        jdbc.update("INSERT INTO inquiry_problem(id, inquiry_id, problem_no, title, problem_status, agent_run_id) "
                + "VALUES (?, ?, 1, '티켓 도구가 만든 문제', 'TICKET_FAILED', ?)", ticketProblem, inquiryId, runId);
        jdbc.update("INSERT INTO agent_ticket_request(run_id, problem_ref, problem_id, request_body) VALUES (?, 'new:t', ?, '{}'::jsonb)",
                runId, ticketProblem);
        Map<String, Object> result = snapshotMap(runId, inquiryId, 1, "담당자에게 전달했습니다.",
                List.of(proposal("new:t", "OBSERVED_SYMPTOM", "INVESTIGATION", systemId, userMessageOf(inquiryId))), List.of());
        ((Map<String, Object>) result.get("result")).put("ticketProposals", List.of(Map.of("problemRef", "new:t")));
        when(client.snapshot(runId.toString())).thenReturn(jsonMapper.valueToTree(result));

        dispatcher.deliver(runId);

        assertThat(jdbc.queryForObject("SELECT run_status FROM agent_run WHERE id=?", String.class, runId)).isEqualTo("SUCCEEDED");
        assertThat(count("SELECT count(*) FROM inquiry_problem WHERE inquiry_id=?", inquiryId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT problem_status FROM inquiry_problem WHERE id=?", String.class, ticketProblem))
                .isEqualTo("TICKET_FAILED");
        assertThat(count("SELECT count(*) FROM standard_request WHERE inquiry_problem_id=?", ticketProblem)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"text-not-in-answer", "two-questions", "ticket-and-question", "unknown-system", "unknown-source",
            "question-without-proposal", "missing-fields", "bad-intent"})
    void 잘못된_질문_결과는_답변과_문제와_질문_수를_함께_저장하지_않는다(String invalid) throws Exception {
        UUID inquiryId = createInquiry("결재 문서가 안 열려요");
        UUID runId = runOf(inquiryId);
        UUID userMessage = userMessageOf(inquiryId);
        Map<String, Object> proposal = proposal("new:p1", "OBSERVED_SYMPTOM", "INVESTIGATION", systemId, userMessage);
        List<Object> questions = new java.util.ArrayList<>(List.of(question("new:p1", "b1", QUESTION, "taskOrScreen")));
        Map<String, Object> snapshot = null;
        switch (invalid) {
            case "text-not-in-answer" -> questions.set(0, question("new:p1", "b1", "답변에 없는 질문입니다", "taskOrScreen"));
            case "two-questions" -> questions.add(question("new:p1", "b2", QUESTION, "taskOrScreen"));
            case "unknown-system" -> proposal.put("reportedSystemCandidateId", UUID.randomUUID().toString());
            case "unknown-source" -> ((List<Map<String, Object>>) proposal.get("facts")).set(0,
                    Map.of("text", "x", "sourceMessageIds", List.of(UUID.randomUUID()), "knowledgeEvidenceIds", List.of()));
            case "question-without-proposal" -> questions.set(0, question("new:other", "b1", QUESTION, "taskOrScreen"));
            case "missing-fields" -> ((Map<String, Object>) questions.get(0)).remove("requestedFields");
            case "bad-intent" -> proposal.put("intent", "NOT_AN_INTENT");
            default -> { }
        }
        snapshot = snapshotMap(runId, inquiryId, 1, QUESTION, List.of(proposal), questions);
        if ("ticket-and-question".equals(invalid)) {
            ((Map<String, Object>) snapshot.get("result")).put("ticketProposals", List.of(Map.of("problemRef", "new:p1")));
        }
        when(client.snapshot(runId.toString())).thenReturn(jsonMapper.valueToTree(snapshot));

        dispatcher.deliver(runId);

        assertThat(jdbc.queryForMap("SELECT run_status, error_code FROM agent_run WHERE id=?", runId))
                .containsEntry("run_status", "FAILED").containsEntry("error_code", "INVALID_OUTPUT");
        assertThat(count("SELECT count(*) FROM message WHERE inquiry_id=?", inquiryId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM inquiry_problem WHERE inquiry_id=?", inquiryId)).isZero();
        assertThat(count("SELECT count(*) FROM standard_request WHERE agent_run_id=?", runId)).isZero();
    }

    @Test
    void 거부한_질문_결과는_사유_코드와_코드_위치를_로그로_남긴다() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                com.hihops.hops.inquiry.service.ProblemQuestionWriter.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            UUID inquiryId = createInquiry("결재 문서가 안 열려요");
            UUID runId = runOf(inquiryId);
            when(client.snapshot(runId.toString())).thenReturn(snapshot(runId, inquiryId, 1, QUESTION,
                    List.of(proposal("new:p1", "OBSERVED_SYMPTOM", "INVESTIGATION", systemId, userMessageOf(inquiryId))),
                    List.of(question("new:p1", "b1", "답변에 없는 질문입니다", "taskOrScreen"))));

            dispatcher.deliver(runId);

            assertThat(appender.list).extracting(event -> event.getFormattedMessage()).anySatisfy(message -> {
                assertThat(message).contains("QUESTION_NOT_IN_ANSWER").contains("runId=" + runId);
                assertThat(message).contains("ProblemQuestionWriter.recordQuestion");
            });
            // 질문 값(AI 결과의 문구)은 로그에 싣지 않는다
            assertThat(appender.list).extracting(event -> event.getFormattedMessage())
                    .noneSatisfy(message -> assertThat(message).contains("답변에 없는 질문입니다"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void 질문_한도_초과_거부는_형식_오류가_아니라_상태_위반으로_로그에_남는다() throws Exception {
        UUID inquiryId = askOnce("결재 문서가 안 열려요");
        UUID problemId = problemOf(inquiryId);
        for (int round = 2; round <= 5; round++) askAgain(inquiryId, problemId, "b" + round, "추가 답변 " + round);
        JsonNode next = secondRunInput(inquiryId, "추가 답변 6");
        UUID sixth = UUID.fromString(next.path("runId").asString());
        when(client.snapshot(sixth.toString())).thenReturn(snapshot(sixth, inquiryId, next.path("inquiryVersion").asInt(), QUESTION,
                List.of(proposal(problemId.toString(), "OBSERVED_SYMPTOM", "INVESTIGATION", systemId, userMessageOf(inquiryId))),
                List.of(question(problemId.toString(), "b6", QUESTION, "taskOrScreen"))));
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                com.hihops.hops.inquiry.service.ProblemQuestionWriter.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            dispatcher.deliver(sixth);

            assertThat(appender.list).extracting(event -> event.getFormattedMessage()).anySatisfy(message -> {
                assertThat(message).contains("INVALID_STATE").doesNotContain("INVALID_FORMAT");
                assertThat(message).contains("InquiryProblem.recordQuestion");
            });
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void 더_새_입력이_생긴_문의의_늦은_질문_결과는_저장하지_않는다() throws Exception {
        UUID inquiryId = createInquiry("결재 문서가 안 열려요");
        UUID runId = runOf(inquiryId);
        when(client.snapshot(runId.toString())).thenReturn(snapshot(runId, inquiryId, 1, QUESTION,
                List.of(proposal("new:p1", "OBSERVED_SYMPTOM", "INVESTIGATION", systemId, userMessageOf(inquiryId))),
                List.of(question("new:p1", "b1", QUESTION, "taskOrScreen"))));
        jdbc.update("UPDATE inquiry SET version = 2 WHERE id = ?", inquiryId);

        dispatcher.deliver(runId);

        assertThat(count("SELECT count(*) FROM inquiry_problem WHERE inquiry_id=?", inquiryId)).isZero();
        assertThat(count("SELECT count(*) FROM message WHERE inquiry_id=?", inquiryId)).isEqualTo(1);
    }

    @Test
    void 확인_대기_중인_기존_문제에는_질문할_수_없고_상태도_바꾸지_않는다() throws Exception {
        UUID inquiryId = askOnce("결재 문서가 안 열려요");
        UUID problemId = problemOf(inquiryId);
        jdbc.update("UPDATE inquiry_problem SET problem_status='GUIDED' WHERE id=?", problemId);
        JsonNode next = secondRunInput(inquiryId, "다시 확인해 주세요");
        UUID secondRun = UUID.fromString(next.path("runId").asString());
        Map<String, Object> proposal = proposal(problemId.toString(), "OBSERVED_SYMPTOM", "INVESTIGATION", systemId,
                userMessageOf(inquiryId));
        when(client.snapshot(secondRun.toString())).thenReturn(snapshot(secondRun, inquiryId, 2, "계속 안내합니다.",
                List.of(proposal), List.of()));

        dispatcher.deliver(secondRun);

        assertThat(jdbc.queryForObject("SELECT run_status FROM agent_run WHERE id=?", String.class, secondRun)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForMap("SELECT problem_status, clarify_count FROM inquiry_problem WHERE id=?", problemId))
                .containsEntry("problem_status", "GUIDED").containsEntry("clarify_count", 1);

        UUID third = secondRunAgain(inquiryId, "한 번 더요");
        when(client.snapshot(third.toString())).thenReturn(snapshot(third, inquiryId, 3, QUESTION, List.of(proposal),
                List.of(question(problemId.toString(), "b2", QUESTION, "taskOrScreen"))));
        dispatcher.deliver(third);
        assertThat(jdbc.queryForMap("SELECT run_status, error_code FROM agent_run WHERE id=?", third))
                .containsEntry("run_status", "FAILED").containsEntry("error_code", "INVALID_OUTPUT");
        assertThat(jdbc.queryForMap("SELECT problem_status, clarify_count FROM inquiry_problem WHERE id=?", problemId))
                .containsEntry("problem_status", "GUIDED").containsEntry("clarify_count", 1);
    }

    @Test
    void 질문_뒤_답을_받은_실행이_담당자_검토_티켓으로_인계하면_질문_수와_이력이_유지된다() throws Exception {
        UUID inquiryId = askOnce("결재 문서가 안 열려요");
        UUID problemId = problemOf(inquiryId);
        UUID source = userMessageOf(inquiryId);
        JsonNode next = secondRunInput(inquiryId, "화면은 잘 모르겠어요");
        UUID secondRun = UUID.fromString(next.path("runId").asString());

        // AI가 티켓 도구(내부 API)로 이미 질문한 문제의 티켓을 만든다. 도구는 실행 중인 실행에만 쓸 수 있다
        jdbc.update("UPDATE agent_run SET run_status = 'RUNNING', started_at = now() WHERE id = ?", secondRun);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/internal/v2/runs/" + secondRun + "/ticket-creations")
                .header("Authorization", "Bearer " + TicketCreationTestBodies.TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(TicketCreationTestBodies.body(problemId.toString(),
                        next.path("inquiryVersion").asInt(), systemId, source, 12))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("STORED"));
        assertThat(count("SELECT count(*) FROM ticket WHERE inquiry_id=?", inquiryId)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT problem_status, clarify_count FROM inquiry_problem WHERE id=?", problemId))
                .containsEntry("problem_status", "TICKET_CREATED").containsEntry("clarify_count", 1);

        // 그 실행의 결과를 반영해도 티켓이 생긴 문제는 되돌리거나 질문 수를 바꾸지 않는다
        jdbc.update("UPDATE agent_run SET run_status = 'QUEUED', started_at = NULL WHERE id = ?", secondRun);
        Map<String, Object> result = snapshotMap(secondRun, inquiryId, next.path("inquiryVersion").asInt(),
                "담당자에게 전달했습니다.",
                List.of(proposal(problemId.toString(), "OBSERVED_SYMPTOM", "INVESTIGATION", systemId, source)), List.of());
        ((Map<String, Object>) result.get("result")).put("ticketProposals", List.of(Map.of("problemRef", problemId.toString())));
        when(client.snapshot(secondRun.toString())).thenReturn(jsonMapper.valueToTree(result));
        dispatcher.deliver(secondRun);

        assertThat(jdbc.queryForObject("SELECT run_status FROM agent_run WHERE id=?", String.class, secondRun)).isEqualTo("SUCCEEDED");
        assertThat(count("SELECT count(*) FROM inquiry_problem WHERE inquiry_id=?", inquiryId)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT problem_status, clarify_count FROM inquiry_problem WHERE id=?", problemId))
                .containsEntry("problem_status", "TICKET_CREATED").containsEntry("clarify_count", 1);
        mockMvc.perform(as(get("/api/v2/inquiries/" + inquiryId))).andExpect(jsonPath("$.problems[0].questionCount").value(1))
                .andExpect(jsonPath("$.problems[0].ticket").exists());
    }

    @Test
    void 질문_뒤_모른다고_답해_시스템을_정하지_못하면_확인_대기로_남고_이력이_유지된다() throws Exception {
        UUID inquiryId = createInquiry("문서가 안 열려요");
        UUID runId = runOf(inquiryId);
        Map<String, Object> asking = proposal("new:p1", "OBSERVED_SYMPTOM", "INVESTIGATION", null, userMessageOf(inquiryId));
        when(client.snapshot(runId.toString())).thenReturn(snapshot(runId, inquiryId, 1, "어느 시스템인가요?",
                List.of(asking), List.of(question("new:p1", "b1", "어느 시스템인가요?", "system"))));
        dispatcher.deliver(runId);
        UUID problemId = problemOf(inquiryId);

        JsonNode next = secondRunInput(inquiryId, "어느 시스템인지 모르겠어요");
        UUID secondRun = UUID.fromString(next.path("runId").asString());
        assertThat(next.path("problems").get(0).path("askedFields").get(0).asString()).isEqualTo("system");
        when(client.snapshot(secondRun.toString())).thenReturn(snapshot(secondRun, inquiryId, 2, "시스템을 확인해야 합니다.",
                List.of(proposal(problemId.toString(), "OBSERVED_SYMPTOM", "INVESTIGATION", null, userMessageOf(inquiryId))),
                List.of()));
        dispatcher.deliver(secondRun);

        assertThat(jdbc.queryForMap("SELECT problem_status, clarify_count FROM inquiry_problem WHERE id=?", problemId))
                .containsEntry("problem_status", "SYSTEM_PENDING").containsEntry("clarify_count", 1);
        mockMvc.perform(as(get("/api/v2/inquiries/" + inquiryId))).andExpect(jsonPath("$.problems[0].state").value("WAITING_SYSTEM"))
                .andExpect(jsonPath("$.problems[0].questionCount").value(1));
    }

    // -- 도우미 -------------------------------------------------------------------------------

    private UUID askOnce(String content) throws Exception {
        UUID inquiryId = createInquiry(content);
        UUID runId = runOf(inquiryId);
        when(client.snapshot(runId.toString())).thenReturn(snapshot(runId, inquiryId, 1, QUESTION,
                List.of(proposal("new:p1", "OBSERVED_SYMPTOM", "INVESTIGATION", systemId, userMessageOf(inquiryId))),
                List.of(question("new:p1", "b1", QUESTION, "taskOrScreen"))));
        dispatcher.deliver(runId);
        assertThat(jdbc.queryForObject("SELECT run_status FROM agent_run WHERE id=?", String.class, runId)).isEqualTo("SUCCEEDED");
        return inquiryId;
    }

    // 사용자가 답하고, 그 답으로 접수한 다음 실행의 입력을 돌려준다
    private JsonNode secondRunInput(UUID inquiryId, String reply) throws Exception {
        int version = jdbc.queryForObject("SELECT version FROM inquiry WHERE id=?", Integer.class, inquiryId);
        UUID sendId = UUID.randomUUID();
        mockMvc.perform(as(post("/api/v2/inquiries/" + inquiryId + "/messages")).header("Idempotency-Key", sendId)
                .contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(Map.of(
                        "requestId", sendId, "expectedInquiryVersion", version, "content", reply, "problemIds", List.of()))))
                .andExpect(status().isOk());
        return jsonMapper.readTree(jdbc.queryForObject("SELECT input_snapshot::text FROM agent_run "
                + "WHERE id=(SELECT current_run_id FROM inquiry WHERE id=?)", String.class, inquiryId));
    }

    private UUID secondRunAgain(UUID inquiryId, String reply) throws Exception {
        return UUID.fromString(secondRunInput(inquiryId, reply).path("runId").asString());
    }

    private void askAgain(UUID inquiryId, UUID problemId, String bundleRef, String reply) throws Exception {
        JsonNode next = secondRunInput(inquiryId, reply);
        UUID run = UUID.fromString(next.path("runId").asString());
        when(client.snapshot(run.toString())).thenReturn(snapshot(run, inquiryId, next.path("inquiryVersion").asInt(), QUESTION,
                List.of(proposal(problemId.toString(), "OBSERVED_SYMPTOM", "INVESTIGATION", systemId, userMessageOf(inquiryId))),
                List.of(question(problemId.toString(), bundleRef, QUESTION, "taskOrScreen"))));
        dispatcher.deliver(run);
        assertThat(jdbc.queryForObject("SELECT run_status FROM agent_run WHERE id=?", String.class, run)).isEqualTo("SUCCEEDED");
    }

    private Map<String, Object> proposal(String ref, String intent, String processingType, UUID system, UUID sourceMessage) {
        Map<String, Object> proposal = new LinkedHashMap<>();
        proposal.put("problemRef", ref);
        proposal.put("summary", "결재 문서가 열리지 않는다");
        proposal.put("intent", intent);
        proposal.put("processingType", processingType);
        proposal.put("reportedSystemCandidateId", system == null ? null : system.toString());
        proposal.put("facts", new java.util.ArrayList<>(List.of(Map.of("text", "결재 문서가 열리지 않는다",
                "sourceMessageIds", List.of(sourceMessage), "knowledgeEvidenceIds", List.of()))));
        proposal.put("assumptions", new java.util.ArrayList<>());
        proposal.put("unknowns", List.of("화면"));
        proposal.put("details", new RequestDetailsResponse(null, null, null, null, "문서가 열리지 않음", null, null, null, List.of()));
        return proposal;
    }

    private static Map<String, Object> question(String ref, String bundleRef, String text, String field) {
        Map<String, Object> question = new LinkedHashMap<>();
        question.put("problemRef", ref);
        question.put("bundleRef", bundleRef);
        question.put("text", text);
        question.put("requestedFields", List.of(field));
        return question;
    }

    private JsonNode snapshot(UUID runId, UUID inquiryId, int version, String answer, List<?> proposals, List<?> questions) {
        return jsonMapper.valueToTree(snapshotMap(runId, inquiryId, version, answer, proposals, questions));
    }

    private Map<String, Object> snapshotMap(UUID runId, UUID inquiryId, int version, String answer, List<?> proposals,
            List<?> questions) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractVersion", "hops-system-1");
        result.put("runId", runId);
        result.put("kind", "INQUIRY_ANALYSIS");
        result.put("inquiryId", inquiryId);
        result.put("inquiryVersion", version);
        result.put("status", "SUCCEEDED");
        result.put("answer", answer);
        result.put("problemProposals", proposals);
        result.put("questions", questions);
        result.put("guide", null);
        result.put("ticketProposals", List.of());
        result.put("caseProposal", null);
        result.put("evidence", new java.util.ArrayList<>());
        result.put("error", null);
        result.put("requiresFollowUp", !questions.isEmpty());
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("contractVersion", "hops-system-1");
        snapshot.put("runId", runId);
        snapshot.put("status", "SUCCEEDED");
        snapshot.put("lastSeq", 0);
        snapshot.put("result", result);
        snapshot.put("error", null);
        return snapshot;
    }

    private void insertKnowledge() {
        String content = "결재 문서 조회는 작성 기간을 먼저 확인합니다.";
        jdbc.update("INSERT INTO knowledge_document(id,title,document_type,it_system_id,access_level,data_split,review_status) "
                + "VALUES (?,'가상 직원 가이드','EMPLOYEE_GUIDE',?,'EMPLOYEE','RAG','APPROVED')", document, systemId);
        jdbc.update("INSERT INTO knowledge_document_version(knowledge_document_id,version_no,content,content_digest,source_metadata,review_status) "
                + "VALUES (?,1,?,?,?::jsonb,'APPROVED')", document, content, digestOf(content),
                "{\"origin\":\"SYNTHETIC\",\"applicability\":\"가상 결재 화면\",\"appliesToSystemVersion\":null}");
    }

    private KnowledgeEvidenceResponse evidence() {
        String content = "결재 문서 조회는 작성 기간을 먼저 확인합니다.";
        return new KnowledgeEvidenceResponse(evidenceId, document, 1, "GUIDE", "가상 직원 가이드", content, systemId, null,
                "가상 결재 화면", "EMPLOYEE", "SYNTHETIC");
    }

    private static String digestOf(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
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
        return jdbc.queryForObject("SELECT id FROM agent_run WHERE inquiry_id = ? ORDER BY created_at LIMIT 1", UUID.class, inquiryId);
    }

    private UUID userMessageOf(UUID inquiryId) {
        return jdbc.queryForObject("SELECT id FROM message WHERE inquiry_id=? AND sender_type='USER' ORDER BY sequence_no LIMIT 1",
                UUID.class, inquiryId);
    }

    private UUID problemOf(UUID inquiryId) {
        return jdbc.queryForObject("SELECT id FROM inquiry_problem WHERE inquiry_id=?", UUID.class, inquiryId);
    }

    private int count(String sql, UUID id) {
        return jdbc.queryForObject(sql, Integer.class, id);
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request) {
        return request.with(jwt().jwt(token -> token.subject(memberId.toString())
                .claim("departmentId", departmentId.toString()).claim("roles", List.of()).claim("policyRevision", 1)
                .claim("primarySystemIds", List.of()).expiresAt(java.time.Instant.now().plusSeconds(600))));
    }
}
