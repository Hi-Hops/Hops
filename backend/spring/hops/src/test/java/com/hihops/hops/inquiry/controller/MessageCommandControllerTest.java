package com.hihops.hops.inquiry.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.hihops.hops.agent.service.AgentRunQueued;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 메시지 전송·정정(POST /api/v2/inquiries/{id}/messages, .../messages/{messageId}/revisions)과 거절 기록(REJECTED)을
 * 실제 테스트 DB로 확인한다 (WIRE_CONTRACT 12.1·12.2·12.8).
 *
 * <p>멱등 처리 때문에 트랜잭션 밖에서 실제로 commit하므로 넣은 행을 {@link #cleanup()}에서 지운다.
 * 시스템 코드는 계약 코드만 응답할 수 있어, 다른 테스트와 겹치지 않는 COMMON_LOGIN을 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"db", "test"})
@RecordApplicationEvents
class MessageCommandControllerTest {

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

    private String inquiryId;

    @BeforeEach
    void createFixtures() throws Exception {
        String suffix = departmentId.toString().substring(0, 8);
        jdbc.update("INSERT INTO department (id, code, name) VALUES (?, ?, '메시지 전송 테스트 부서')", departmentId, "MSG_" + suffix);
        for (UUID id : List.of(memberId, otherMemberId)) {
            jdbc.update("INSERT INTO member (id, login_id, employee_no, password_hash, name, department_id) "
                    + "VALUES (?, ?, ?, 'unused-test-hash', '메시지 전송 테스트 회원', ?)", id, id.toString(), id.toString(), departmentId);
        }
        jdbc.update("INSERT INTO it_system (id, code, name, primary_owner_member_id) VALUES (?, 'COMMON_LOGIN', '테스트 로그인', ?)",
                systemId, otherMemberId);
        jdbc.update("INSERT INTO system_feature (id, it_system_id, code, name) VALUES (?, ?, ?, '로그인')",
                featureId, systemId, "TEST_FEATURE_" + suffix);

        UUID requestId = UUID.randomUUID();
        mockMvc.perform(as(memberId, post("/api/v2/inquiries"))
                        .header("Idempotency-Key", requestId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("requestId", requestId, "content", "로그인이 안 돼요"))))
                .andExpect(status().isOk());
        inquiryId = jdbc.queryForObject("SELECT id FROM inquiry WHERE requester_member_id = ?", UUID.class, memberId)
                .toString();
    }

    @AfterEach
    void cleanup() {
        String mine = "SELECT id FROM inquiry WHERE requester_member_id IN (?, ?)";
        jdbc.update("UPDATE inquiry SET current_run_id = NULL WHERE requester_member_id IN (?, ?)", memberId, otherMemberId);
        jdbc.update("DELETE FROM ticket WHERE inquiry_id IN (" + mine + ")", memberId, otherMemberId);
        jdbc.update("DELETE FROM standard_request WHERE inquiry_problem_id IN "
                + "(SELECT id FROM inquiry_problem WHERE inquiry_id IN (" + mine + "))", memberId, otherMemberId);
        jdbc.update("DELETE FROM inquiry_problem WHERE inquiry_id IN (" + mine + ")", memberId, otherMemberId);
        jdbc.update("DELETE FROM agent_run WHERE inquiry_id IN (" + mine + ")", memberId, otherMemberId);
        jdbc.update("DELETE FROM message_revision WHERE message_id IN "
                + "(SELECT id FROM message WHERE inquiry_id IN (" + mine + "))", memberId, otherMemberId);
        jdbc.update("DELETE FROM message WHERE inquiry_id IN (" + mine + ")", memberId, otherMemberId);
        jdbc.update("DELETE FROM inquiry WHERE requester_member_id IN (?, ?)", memberId, otherMemberId);
        jdbc.update("DELETE FROM idempotency_key WHERE actor_member_id IN (?, ?)", memberId, otherMemberId);
        jdbc.update("DELETE FROM system_feature WHERE id = ?", featureId);
        jdbc.update("DELETE FROM it_system WHERE id = ?", systemId);
        jdbc.update("DELETE FROM member WHERE id IN (?, ?)", memberId, otherMemberId);
        jdbc.update("DELETE FROM department WHERE id = ?", departmentId);
    }

    @Test
    void 메시지를_저장하고_버전을_올려_이전_실행을_대체한_새_실행을_접수한다() throws Exception {
        String firstRunId = currentRunId();
        UUID requestId = UUID.randomUUID();

        send(memberId, requestId, 1, "  비밀번호를 바꾼 뒤부터예요\n", List.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.resource.type").value("INQUIRY"))
                .andExpect(jsonPath("$.resource.id").value(inquiryId));

        assertThat(jdbc.queryForObject("SELECT version FROM inquiry WHERE id = ?::uuid", Integer.class, inquiryId))
                .isEqualTo(2);
        Map<String, Object> message = jdbc.queryForMap(
                "SELECT * FROM message WHERE inquiry_id = ?::uuid AND sequence_no = 2", inquiryId);
        assertThat(message.get("sender_type")).isEqualTo("USER");
        assertThat(message.get("content")).isEqualTo("  비밀번호를 바꾼 뒤부터예요\n");
        JsonNode payload = jsonMapper.readTree(message.get("payload").toString());
        assertThat(payload.path("requestId").asString()).isEqualTo(requestId.toString());
        assertThat(payload.path("problemLinks").isEmpty()).isTrue();

        // 이전 대기 실행은 대체되고, 새 버전의 실행이 현재 실행이다
        assertThat(jdbc.queryForObject("SELECT run_status FROM agent_run WHERE id = ?::uuid", String.class, firstRunId))
                .isEqualTo("CANCELLED");
        String nextRunId = currentRunId();
        assertThat(nextRunId).isNotEqualTo(firstRunId);
        Map<String, Object> next = jdbc.queryForMap("SELECT * FROM agent_run WHERE id = ?::uuid", nextRunId);
        assertThat(next.get("run_status")).isEqualTo("QUEUED");
        assertThat(next.get("input_version")).isEqualTo(2);
        assertThat(next.get("trigger_message_id")).isEqualTo(message.get("id"));
        JsonNode input = jsonMapper.readTree(next.get("input_snapshot").toString());
        assertThat(input.path("inquiryVersion").asInt()).isEqualTo(2);
        assertThat(input.path("messages").size()).isEqualTo(2);
        assertThat(input.path("messages").get(1).path("id").asString()).isEqualTo(message.get("id").toString());
        assertThat(input.path("problems").isEmpty()).isTrue();
        assertThat(input.path("problemIds").isEmpty()).isTrue();
        assertThat(events.stream(AgentRunQueued.class)).contains(new AgentRunQueued(UUID.fromString(nextRunId)));

        mockMvc.perform(as(memberId, get("/api/v2/runs/" + firstRunId)))
                .andExpect(jsonPath("$.status").value("SUPERSEDED"))
                .andExpect(jsonPath("$.error").isEmpty());
        mockMvc.perform(as(memberId, get("/api/v2/inquiries/" + inquiryId)))
                .andExpect(jsonPath("$.inquiryVersion").value(2))
                .andExpect(jsonPath("$.currentRun.id").value(nextRunId))
                .andExpect(jsonPath("$.currentRun.inquiryVersion").value(2));
        mockMvc.perform(as(memberId, get("/api/v2/inquiries/" + inquiryId + "/messages")))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[1].requestId").value(requestId.toString()));

        // 같은 요청 키는 처음 결과이고 메시지·실행을 더 만들지 않는다
        send(memberId, requestId, 1, "  비밀번호를 바꾼 뒤부터예요\n", List.of()).andExpect(status().isOk());
        assertThat(count("SELECT count(*) FROM message WHERE inquiry_id = ?::uuid")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM agent_run WHERE inquiry_id = ?::uuid")).isEqualTo(2);
    }

    @Test
    void 버전이_다르면_409이고_같은_키는_기록한_거절을_돌려준다() throws Exception {
        UUID requestId = UUID.randomUUID();

        send(memberId, requestId, 2, "비밀번호를 바꾼 뒤부터예요", List.of())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INQUIRY_VERSION_CONFLICT"));
        assertThat(count("SELECT count(*) FROM message WHERE inquiry_id = ?::uuid")).isEqualTo(1);

        // 다른 메시지로 버전이 2가 돼 같은 본문이 이제 맞는 버전이어도, 같은 키는 처음 거절이다
        send(memberId, UUID.randomUUID(), 1, "다른 메시지", List.of()).andExpect(status().isOk());
        send(memberId, requestId, 2, "비밀번호를 바꾼 뒤부터예요", List.of())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INQUIRY_VERSION_CONFLICT"));
        assertThat(count("SELECT count(*) FROM message WHERE inquiry_id = ?::uuid")).isEqualTo(2);

        mockMvc.perform(as(memberId, get("/api/v2/operations/by-request/" + requestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("REJECTED"))
                .andExpect(jsonPath("$.resource").isEmpty())
                .andExpect(jsonPath("$.error.code").value("INQUIRY_VERSION_CONFLICT"));
    }

    @Test
    void 남의_문의는_404_잘못된_입력과_다른_문의의_문제는_400이다() throws Exception {
        send(otherMemberId, UUID.randomUUID(), 1, "남의 문의", List.of())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        send(memberId, UUID.randomUUID(), 1, "　\n", List.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("content"));
        send(memberId, UUID.randomUUID(), 1, "어느 문제인지", List.of(UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        assertThat(count("SELECT count(*) FROM message WHERE inquiry_id = ?::uuid")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM inquiry WHERE id = ?::uuid", Integer.class, inquiryId))
                .isEqualTo(1);
    }

    @Test
    void 끝난_문의는_재오픈하지_않고_새_문의로_안내하며_저장하지_않는다() throws Exception {
        String runId = currentRunId();
        for (String closed : List.of("RESOLVED", "CLOSED")) {
            jdbc.update("UPDATE inquiry SET inquiry_status = ? WHERE id = ?::uuid", closed, inquiryId);
            // 해결 확인은 입력 버전을 올리지 않으므로 화면이 본 버전 그대로 보내도 상태로 거절해야 한다
            send(memberId, UUID.randomUUID(), 1, "참고로 다른 PC에서도 같았어요", List.of())
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_STATE"))
                    .andExpect(jsonPath("$.message").value("종료된 문의입니다. 같은 현상은 새 문의로 접수해 주세요."));
        }

        Map<String, Object> inquiry = jdbc.queryForMap("SELECT * FROM inquiry WHERE id = ?::uuid", inquiryId);
        assertThat(inquiry.get("version")).isEqualTo(1);
        assertThat(inquiry.get("current_run_id").toString()).isEqualTo(runId);
        assertThat(count("SELECT count(*) FROM message WHERE inquiry_id = ?::uuid")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM agent_run WHERE inquiry_id = ?::uuid")).isEqualTo(1);
    }

    @Test
    void 기존_문제는_최신_적용_요청서로_AI_입력에_넣고_고른_문제만_답을_받은_것으로_본다() throws Exception {
        // 첫 분석이 문제 두 개를 만들고 각각 질문했다 (요청서는 적용된 실행의 것만 쓴다)
        String firstRunId = succeedFirstRun();
        String firstMessageId = jdbc.queryForObject("SELECT id FROM message WHERE inquiry_id = ?::uuid AND sequence_no = 1",
                UUID.class, inquiryId).toString();
        UUID answered = insertProblem(1, "CLARIFYING", 2, null, firstRunId);
        UUID waiting = insertProblem(2, "CLARIFYING", 1, null, firstRunId);
        // 첫 메시지 전체가 answered 문제의 원문이다
        jdbc.update("UPDATE message SET payload = jsonb_set(payload, '{problemLinks}', CAST(? AS jsonb)) WHERE id = ?::uuid",
                "[{\"problemId\": \"" + answered + "\", \"revision\": 1, \"spans\": [{\"start\": 0, \"end\": 9}]}]",
                firstMessageId);
        insertStandardRequest(answered, firstRunId, "로그인 실패 조사",
                "[{\"text\": \"로그인이 안 돼요\", \"sourceMessageIds\": [\"" + firstMessageId + "\"], \"knowledgeEvidenceIds\": []}]");
        insertStandardRequest(waiting, firstRunId, "비밀번호 변경 문의", "[]");
        jdbc.update("UPDATE inquiry SET inquiry_status = 'WAITING_USER' WHERE id = ?::uuid", inquiryId);

        String content = "오류 문구는 '계정 잠김'이에요";
        send(memberId, UUID.randomUUID(), 1, content, List.of(answered)).andExpect(status().isOk());

        JsonNode payload = jsonMapper.readTree(jdbc.queryForObject(
                "SELECT payload FROM message WHERE inquiry_id = ?::uuid AND sequence_no = 2", String.class, inquiryId));
        JsonNode link = payload.path("problemLinks").get(0);
        assertThat(link.path("problemId").asString()).isEqualTo(answered.toString());
        assertThat(link.path("spans").get(0).path("end").asInt()).isEqualTo(content.codePointCount(0, content.length()));
        assertThat(jdbc.queryForObject("SELECT inquiry_status FROM inquiry WHERE id = ?::uuid", String.class, inquiryId))
                .isEqualTo("IN_PROGRESS");

        JsonNode input = currentInput();
        assertThat(input.path("problemIds").get(0).asString()).isEqualTo(answered.toString());
        assertThat(input.path("messages").get(0).path("problemIds").get(0).asString()).isEqualTo(answered.toString());
        assertThat(input.path("messages").get(1).path("problemIds").get(0).asString()).isEqualTo(answered.toString());
        assertThat(input.path("problems").size()).isEqualTo(2);
        assertThat(input.path("existingWorkItems").isEmpty()).isTrue();
        JsonNode problem = input.path("problems").get(0);
        assertThat(problem.path("id").asString()).isEqualTo(answered.toString());
        assertThat(problem.path("summary").asString()).isEqualTo("로그인 실패 조사");
        assertThat(problem.path("intent").asString()).isEqualTo("OBSERVED_SYMPTOM");
        assertThat(problem.path("processingType").asString()).isEqualTo("INVESTIGATION");
        assertThat(problem.path("reportedSystemId").asString()).isEqualTo(systemId.toString());
        assertThat(problem.path("reportedFeatureId").asString()).isEqualTo(featureId.toString());
        assertThat(problem.path("state").asString()).isEqualTo("ANALYZING"); // 이번 메시지로 답을 받았다
        assertThat(problem.path("questionCount").asInt()).isEqualTo(2);
        assertThat(problem.path("resolutionContext").isNull()).isTrue();
        JsonNode request = problem.path("standardRequest");
        assertThat(request.path("summary").asString()).isEqualTo("로그인 실패 조사");
        assertThat(request.path("confirmedFacts").get(0).path("sourceMessageIds").get(0).asString())
                .isEqualTo(firstMessageId);
        assertThat(request.path("unknowns").get(0).asString()).isEqualTo("오류 문구");
        assertThat(request.path("details").path("actualBehavior").asString()).isEqualTo("로그인 실패");
        assertThat(request.path("details").has("acceptanceCriteria")).isTrue();
        // 고르지 않은 문제는 아직 답을 기다린다
        JsonNode other = input.path("problems").get(1);
        assertThat(other.path("id").asString()).isEqualTo(waiting.toString());
        assertThat(other.path("state").asString()).isEqualTo("WAITING_USER");
        assertThat(other.path("questionCount").asInt()).isEqualTo(1);
    }

    @Test
    void 대상을_고르지_않으면_질문_중인_문제는_모두_답을_기다린다() throws Exception {
        String firstRunId = succeedFirstRun();
        UUID problemId = insertProblem(1, "CLARIFYING", 1, null, firstRunId);
        insertStandardRequest(problemId, firstRunId, "로그인 실패 조사", "[]");

        send(memberId, UUID.randomUUID(), 1, "어제부터 그래요", List.of()).andExpect(status().isOk());

        JsonNode input = currentInput();
        assertThat(input.path("problemIds").isEmpty()).isTrue();
        assertThat(input.path("problems").get(0).path("state").asString()).isEqualTo("WAITING_USER");
    }

    @Test
    void 기존_티켓_생성_결과를_작업_결과로_넣어_다시_만들지_않게_한다() throws Exception {
        String firstRunId = succeedFirstRun();
        UUID stored = insertProblem(1, "TICKET_CREATED", 0, "STORED", firstRunId);
        UUID notCreated = insertProblem(2, "TICKET_FAILED", 0, "NOT_CREATED", firstRunId);
        UUID storedRequest = insertStandardRequest(stored, firstRunId, "로그인 실패 조사", "[]");
        insertStandardRequest(notCreated, firstRunId, "권한 요청", "[]");
        UUID ticketId = UUID.randomUUID();
        jdbc.update("INSERT INTO ticket (id, ticket_number, inquiry_id, inquiry_problem_id, standard_request_id, title, detail, "
                + "work_type, ticket_status, responsible_system_id, assignee_member_id) "
                + "VALUES (?, ?, ?::uuid, ?, ?, '티켓', '내용', 'INVESTIGATION', 'OPEN', ?, ?)",
                ticketId, "TK-" + ticketId.toString().substring(0, 8), inquiryId, stored, storedRequest, systemId,
                otherMemberId);

        send(memberId, UUID.randomUUID(), 1, "다른 PC에서도 같아요", List.of()).andExpect(status().isOk());

        JsonNode input = currentInput();
        assertThat(input.path("problems").get(0).path("state").asString()).isEqualTo("IN_SERVICE");
        assertThat(input.path("problems").get(1).path("state").asString()).isEqualTo("TICKET_PENDING");
        JsonNode items = input.path("existingWorkItems");
        assertThat(items.size()).isEqualTo(2);
        assertThat(items.get(0).path("workItemId").asString()).isEqualTo(stored.toString());
        assertThat(items.get(0).path("problemId").asString()).isEqualTo(stored.toString());
        assertThat(items.get(0).path("inquiryVersion").asInt()).isEqualTo(1);
        assertThat(items.get(0).path("outcome").asString()).isEqualTo("STORED");
        assertThat(items.get(0).path("ticketId").asString()).isEqualTo(ticketId.toString());
        // 미생성 확인은 진행 중과 구분하고 티켓 ID가 없다
        assertThat(items.get(1).path("outcome").asString()).isEqualTo("NOT_CREATED");
        assertThat(items.get(1).path("ticketId").isNull()).isTrue();
    }

    @Test
    void 정정하면_원문을_이력에_남기고_같은_메시지의_차수를_올려_새_실행을_접수한다() throws Exception {
        String firstRunId = currentRunId();
        String messageId = firstMessageId();
        UUID createRequestId = UUID.fromString(jsonMapper.readTree(jdbc.queryForObject(
                "SELECT payload FROM message WHERE id = ?::uuid", String.class, messageId)).path("requestId").asString());
        UUID reviseRequestId = UUID.randomUUID();

        revise(memberId, messageId, reviseRequestId, 1, 1, "  로그인 버튼을 눌러도 반응이 없어요\n")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.resource.type").value("INQUIRY"))
                .andExpect(jsonPath("$.resource.id").value(inquiryId));

        Map<String, Object> message = jdbc.queryForMap("SELECT * FROM message WHERE id = ?::uuid", messageId);
        assertThat(message.get("content")).isEqualTo("  로그인 버튼을 눌러도 반응이 없어요\n");
        assertThat(message.get("revision")).isEqualTo(2);
        assertThat(message.get("sequence_no")).isEqualTo(1); // 대화의 같은 자리에 남는다
        JsonNode payload = jsonMapper.readTree(message.get("payload").toString());
        assertThat(payload.path("requestId").asString()).isEqualTo(createRequestId.toString()); // 처음 명령 ID 유지
        assertThat(payload.path("revisionRequestIds").path("2").asString()).isEqualTo(reviseRequestId.toString());
        assertThat(jdbc.queryForList("SELECT revision_no || ':' || content FROM message_revision WHERE message_id = ?::uuid",
                String.class, messageId)).containsExactly("1:로그인이 안 돼요");
        assertThat(jdbc.queryForObject("SELECT version FROM inquiry WHERE id = ?::uuid", Integer.class, inquiryId))
                .isEqualTo(2);

        // 이전 실행은 대체되고, 정정한 메시지로 시작한 새 버전 실행이 현재 실행이다
        assertThat(jdbc.queryForObject("SELECT run_status FROM agent_run WHERE id = ?::uuid", String.class, firstRunId))
                .isEqualTo("CANCELLED");
        String nextRunId = currentRunId();
        Map<String, Object> next = jdbc.queryForMap("SELECT * FROM agent_run WHERE id = ?::uuid", nextRunId);
        assertThat(next.get("run_status")).isEqualTo("QUEUED");
        assertThat(next.get("input_version")).isEqualTo(2);
        assertThat(next.get("trigger_message_id").toString()).isEqualTo(messageId);
        JsonNode input = currentInput();
        assertThat(input.path("messages").size()).isEqualTo(1);
        assertThat(input.path("messages").get(0).path("revision").asInt()).isEqualTo(2);
        assertThat(input.path("messages").get(0).path("content").asString()).isEqualTo("  로그인 버튼을 눌러도 반응이 없어요\n");
        assertThat(events.stream(AgentRunQueued.class)).contains(new AgentRunQueued(UUID.fromString(nextRunId)));

        mockMvc.perform(as(memberId, get("/api/v2/inquiries/" + inquiryId + "/messages")))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(messageId))
                .andExpect(jsonPath("$.items[0].revision").value(2))
                .andExpect(jsonPath("$.items[0].requestId").value(createRequestId.toString()))
                .andExpect(jsonPath("$.items[0].revisions[0].revisionNo").value(1))
                .andExpect(jsonPath("$.items[0].revisions[0].content").value("로그인이 안 돼요"));

        // 정정으로 시작한 실행이 실패하면 오류의 requestId는 그 정정 요청이다
        jdbc.update("UPDATE agent_run SET run_status = 'FAILED', error_code = 'MODEL_UNAVAILABLE', completed_at = now() "
                + "WHERE id = ?::uuid", nextRunId);
        mockMvc.perform(as(memberId, get("/api/v2/runs/" + nextRunId)))
                .andExpect(jsonPath("$.error.code").value("MODEL_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.requestId").value(reviseRequestId.toString()));
        // 처음 실행의 오류는 그대로 처음 명령 ID다
        jdbc.update("UPDATE agent_run SET run_status = 'FAILED', error_code = 'MODEL_UNAVAILABLE' WHERE id = ?::uuid",
                firstRunId);
        mockMvc.perform(as(memberId, get("/api/v2/runs/" + firstRunId)))
                .andExpect(jsonPath("$.error.requestId").value(createRequestId.toString()));

        // 같은 요청 키는 처음 결과이고 이력·실행을 더 만들지 않는다
        revise(memberId, messageId, reviseRequestId, 1, 1, "  로그인 버튼을 눌러도 반응이 없어요\n").andExpect(status().isOk());
        assertThat(count("SELECT count(*) FROM message_revision r JOIN message m ON m.id = r.message_id "
                + "WHERE m.inquiry_id = ?::uuid")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM agent_run WHERE inquiry_id = ?::uuid")).isEqualTo(2);
    }

    @Test
    void 정정할_수_없으면_거절하고_원문을_바꾸지_않는다() throws Exception {
        String messageId = firstMessageId();
        UUID aiMessageId = UUID.randomUUID();
        jdbc.update("INSERT INTO message (id, inquiry_id, sequence_no, sender_type, content, payload, message_status) "
                + "VALUES (?, ?::uuid, 2, 'AI', '어떤 화면인가요?', '{}', 'COMPLETED')", aiMessageId, inquiryId);

        revise(memberId, messageId, UUID.randomUUID(), 1, 2, "다른 내용")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MESSAGE_REVISION_CONFLICT"));
        revise(memberId, messageId, UUID.randomUUID(), 2, 1, "다른 내용")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INQUIRY_VERSION_CONFLICT"));
        // AI 메시지·없는 메시지·남의 문의는 존재를 알리지 않는다
        for (String target : List.of(aiMessageId.toString(), UUID.randomUUID().toString())) {
            revise(memberId, target, UUID.randomUUID(), 1, 1, "다른 내용")
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
        revise(otherMemberId, messageId, UUID.randomUUID(), 1, 1, "다른 내용").andExpect(status().isNotFound());
        revise(memberId, messageId, UUID.randomUUID(), 1, 1, "\u3000").andExpect(status().isBadRequest());
        jdbc.update("UPDATE inquiry SET inquiry_status = 'RESOLVED' WHERE id = ?::uuid", inquiryId);
        revise(memberId, messageId, UUID.randomUUID(), 1, 1, "다른 내용")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));

        Map<String, Object> message = jdbc.queryForMap("SELECT * FROM message WHERE id = ?::uuid", messageId);
        assertThat(message.get("content")).isEqualTo("로그인이 안 돼요");
        assertThat(message.get("revision")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM message_revision r JOIN message m ON m.id = r.message_id "
                + "WHERE m.inquiry_id = ?::uuid")).isZero();
        assertThat(jdbc.queryForObject("SELECT version FROM inquiry WHERE id = ?::uuid", Integer.class, inquiryId))
                .isEqualTo(1);
    }

    @Test
    void 정정한_메시지의_문제_연결을_새_원문으로_잇고_그_문제를_대상으로_분석한다() throws Exception {
        String firstRunId = succeedFirstRun();
        UUID linked = insertProblem(1, "CLARIFYING", 1, null, firstRunId);
        UUID partial = insertProblem(2, "CLARIFYING", 1, null, firstRunId);
        insertStandardRequest(linked, firstRunId, "로그인 실패 조사", "[]");
        insertStandardRequest(partial, firstRunId, "비밀번호 문의", "[]");
        String content = "오류 문구는 '계정 잠김'이에요";
        send(memberId, UUID.randomUUID(), 1, content, List.of(linked)).andExpect(status().isOk());
        String messageId = jdbc.queryForObject("SELECT id FROM message WHERE inquiry_id = ?::uuid AND sequence_no = 2",
                UUID.class, inquiryId).toString();
        // 일부 구간에만 걸린 연결(분석이 만든 것)은 새 원문에 옮길 수 없다
        jdbc.update("UPDATE message SET payload = jsonb_set(payload, '{problemLinks}', payload -> 'problemLinks' || "
                + "CAST(? AS jsonb)) WHERE id = ?::uuid",
                "[{\"problemId\": \"" + partial + "\", \"revision\": 1, \"spans\": [{\"start\": 0, \"end\": 5}]}]",
                messageId);

        String revised = "오류 문구는 '계정이 잠겼습니다'예요";
        revise(memberId, messageId, UUID.randomUUID(), 2, 1, revised).andExpect(status().isOk());

        JsonNode links = jsonMapper.readTree(jdbc.queryForObject("SELECT payload FROM message WHERE id = ?::uuid",
                String.class, messageId)).path("problemLinks");
        assertThat(links.size()).isEqualTo(3); // 1차 연결 2개는 정정 이력 투영을 위해 남는다
        JsonNode carried = links.get(2);
        assertThat(carried.path("problemId").asString()).isEqualTo(linked.toString());
        assertThat(carried.path("revision").asInt()).isEqualTo(2);
        assertThat(carried.path("spans").get(0).path("end").asInt()).isEqualTo(revised.codePointCount(0, revised.length()));

        JsonNode input = currentInput();
        assertThat(input.path("inquiryVersion").asInt()).isEqualTo(3);
        assertThat(input.path("problemIds").size()).isEqualTo(1);
        assertThat(input.path("problemIds").get(0).asString()).isEqualTo(linked.toString());
        assertThat(input.path("messages").get(1).path("problemIds").size()).isEqualTo(1);
        assertThat(input.path("problems").get(0).path("state").asString()).isEqualTo("ANALYZING");
        assertThat(input.path("problems").get(1).path("state").asString()).isEqualTo("WAITING_USER");
        mockMvc.perform(as(memberId, get("/api/v2/inquiries/" + inquiryId + "/messages")))
                .andExpect(jsonPath("$.items[1].problemIds.length()").value(1))
                .andExpect(jsonPath("$.items[1].problemIds[0]").value(linked.toString()));
    }

    private ResultActions revise(UUID actorId, String messageId, UUID requestId, int expectedInquiryVersion,
            int expectedMessageRevision, String content) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedInquiryVersion", expectedInquiryVersion);
        body.put("expectedMessageRevision", expectedMessageRevision);
        body.put("content", content);
        return mockMvc.perform(as(actorId, post("/api/v2/inquiries/" + inquiryId + "/messages/" + messageId + "/revisions"))
                .header("Idempotency-Key", requestId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body)));
    }

    private String firstMessageId() {
        return jdbc.queryForObject("SELECT id FROM message WHERE inquiry_id = ?::uuid AND sequence_no = 1", UUID.class,
                inquiryId).toString();
    }

    private String succeedFirstRun() {
        String runId = currentRunId();
        jdbc.update("UPDATE agent_run SET run_status = 'SUCCEEDED', started_at = now(), completed_at = now() "
                + "WHERE id = ?::uuid", runId);
        return runId;
    }

    private UUID insertProblem(int problemNo, String status, int clarifyCount, String creationOutcome, String runId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO inquiry_problem (id, inquiry_id, problem_no, title, problem_status, clarify_count, "
                + "creation_outcome, creation_input_version, agent_run_id) VALUES (?, ?::uuid, ?, ?, ?, ?, ?, ?, ?::uuid)",
                id, inquiryId, problemNo, "문제 " + problemNo, status, clarifyCount, creationOutcome,
                creationOutcome == null ? null : 1, runId);
        return id;
    }

    private UUID insertStandardRequest(UUID problemId, String runId, String summary, String confirmedFacts) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO standard_request (id, inquiry_problem_id, inquiry_version, agent_run_id, intent, work_type, "
                + "reported_system_id, reported_feature_id, responsible_system_id, summary, confirmed_facts, missing_items, "
                + "type_detail) VALUES (?, ?, 1, ?::uuid, 'OBSERVED_SYMPTOM', 'INVESTIGATION', ?, ?, ?, ?, CAST(? AS jsonb), "
                + "CAST(? AS jsonb), CAST(? AS jsonb))",
                id, problemId, runId, systemId, featureId, systemId, summary, confirmedFacts, "[\"오류 문구\"]",
                "{\"actualBehavior\": \"로그인 실패\"}");
        return id;
    }

    private JsonNode currentInput() {
        return jsonMapper.readTree(jdbc.queryForObject("SELECT input_snapshot FROM agent_run WHERE id = ?::uuid",
                String.class, currentRunId()));
    }

    private ResultActions send(UUID actorId, UUID requestId, int expectedInquiryVersion, String content,
            List<UUID> problemIds) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedInquiryVersion", expectedInquiryVersion);
        body.put("content", content);
        body.put("problemIds", problemIds);
        return mockMvc.perform(as(actorId, post("/api/v2/inquiries/" + inquiryId + "/messages"))
                .header("Idempotency-Key", requestId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body)));
    }

    private String currentRunId() {
        return jdbc.queryForObject("SELECT current_run_id FROM inquiry WHERE id = ?::uuid", UUID.class, inquiryId)
                .toString();
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class, inquiryId);
    }

    private static MockHttpServletRequestBuilder as(UUID actorId, MockHttpServletRequestBuilder request) {
        return request.with(jwt().jwt(token -> token.subject(actorId.toString())));
    }
}
