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
import com.hihops.hops.realtime.dto.MessageStoredEvent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 시스템 선택 (WIRE_CONTRACT 12.2 POST /inquiries/{inquiryId}/problems/{problemId}/system-confirmations, 본인·미확정 시스템).
 *
 * <p>보고 시스템이 정해지지 않은 문제(WAITING_SYSTEM 또는 분석 전 질문 중)에 문의자가 지원 시스템을 고르면 입력 버전을 1 올리고
 * 그 문제를 대상으로 새 분석을 접수한다. 질문 수는 그대로다. 선택은 새 메시지가 아니라 문제에 연결된 원문의 보고 시스템
 * 정정 기록(reportedSystemCorrection)으로 남긴다. 멱등 처리 때문에 거래 밖에서 실제로 commit하므로 넣은 행은 끝나면 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"db", "test"})
@RecordApplicationEvents
class SystemConfirmationControllerTest {

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
    private final UUID ownerId = UUID.randomUUID();
    private final UUID systemId = UUID.randomUUID();
    private final UUID loginSystemId = UUID.randomUUID();

    private String inquiryId;

    @BeforeEach
    void createFixtures() throws Exception {
        String suffix = departmentId.toString().substring(0, 8);
        jdbc.update("INSERT INTO department (id, code, name) VALUES (?, ?, '시스템 선택 테스트 부서')", departmentId, "SYS_" + suffix);
        for (UUID id : List.of(memberId, otherMemberId, ownerId)) {
            jdbc.update("INSERT INTO member (id, login_id, employee_no, password_hash, name, department_id) "
                    + "VALUES (?, ?, ?, 'unused-test-hash', '시스템 선택 테스트 회원', ?)", id, id.toString(), id.toString(), departmentId);
        }
        jdbc.update("INSERT INTO it_system (id, code, name, primary_owner_member_id) VALUES (?, 'ELECTRONIC_APPROVAL', '전자결재', ?)",
                systemId, ownerId);
        jdbc.update("INSERT INTO it_system (id, code, name, primary_owner_member_id) VALUES (?, 'COMMON_LOGIN', '공통 로그인', ?)",
                loginSystemId, ownerId);

        UUID requestId = UUID.randomUUID();
        mockMvc.perform(as(memberId, post("/api/v2/inquiries"))
                        .header("Idempotency-Key", requestId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("requestId", requestId, "content", "화면이 안 열려요"))))
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
        jdbc.update("DELETE FROM it_system WHERE primary_owner_member_id = ?", ownerId);
        jdbc.update("DELETE FROM member WHERE id IN (?, ?, ?)", memberId, otherMemberId, ownerId);
        jdbc.update("DELETE FROM department WHERE id = ?", departmentId);
    }

    // ---- 성공 ----

    @Test
    void 시스템_대기_문제에_시스템을_고르면_버전을_올리고_그_문제를_대상으로_새_실행을_접수한다() throws Exception {
        String firstRun = succeedFirstRun();
        UUID problem = waitingSystemProblem(firstRun, 3);
        UUID requestId = UUID.randomUUID();

        confirm(memberId, problem, requestId, 1, loginSystemId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.resource.type").value("INQUIRY"))
                .andExpect(jsonPath("$.resource.id").value(inquiryId));

        assertThat(jdbc.queryForObject("SELECT version FROM inquiry WHERE id = ?::uuid", Integer.class, inquiryId)).isEqualTo(2);
        String nextRun = currentRunId();
        assertThat(nextRun).isNotEqualTo(firstRun);
        assertThat(jdbc.queryForObject("SELECT run_status FROM agent_run WHERE id = ?::uuid", String.class, nextRun)).isEqualTo("QUEUED");
        JsonNode input = currentInput();
        assertThat(input.path("inquiryVersion").asInt()).isEqualTo(2);
        assertThat(problemIdsOf(input)).containsExactly(problem.toString());
        JsonNode inputProblem = input.path("problems").get(0);
        assertThat(inputProblem.path("reportedSystemId").asString()).isEqualTo(loginSystemId.toString());
        assertThat(inputProblem.path("state").asString()).isEqualTo("ANALYZING");
        assertThat(inputProblem.path("questionCount").asInt()).isEqualTo(3);
        String message = firstMessageId();
        assertThat(inputProblem.path("reportedSystemCorrectionMessageId").asString()).isEqualTo(message);

        // 선택은 문제에 연결된 원문의 정정 기록으로 남고, 접수한 실행과 명령이 이어진다
        JsonNode payload = payloadOf(message);
        assertThat(payload.path("reportedSystemCorrection").path("problemId").asString()).isEqualTo(problem.toString());
        assertThat(payload.path("reportedSystemCorrection").path("systemId").asString()).isEqualTo(loginSystemId.toString());
        assertThat(payload.path("reportedSystemCorrection").path("previousSystemId").isNull()).isTrue();
        assertThat(payload.path("reportedSystemCorrection").path("inquiryVersion").asInt()).isEqualTo(2);
        assertThat(payload.path("runRequestIds").path(nextRun).asString()).isEqualTo(requestId.toString());
        assertThat(count("SELECT count(*) FROM message WHERE inquiry_id = ?::uuid")).isEqualTo(1); // 새 메시지는 없다

        // 질문 수는 그대로이고, 분석을 기다리는 상태로 바뀐다
        assertThat(jdbc.queryForMap("SELECT problem_status, clarify_count FROM inquiry_problem WHERE id = ?", problem))
                .containsEntry("problem_status", "CLARIFYING").containsEntry("clarify_count", 3);
        assertThat(events.stream(AgentRunQueued.class)).contains(new AgentRunQueued(UUID.fromString(nextRun)));
        assertThat(events.stream(MessageStoredEvent.class).filter(stored -> stored.inquiryVersion() == 2)).hasSize(1);
    }

    @Test
    void 시스템을_골라도_AI_입력의_요약과_요청서_본문은_그대로_보낸다() throws Exception {
        String firstRun = succeedFirstRun();
        UUID problem = waitingSystemProblem(firstRun, 2);

        confirm(memberId, problem, UUID.randomUUID(), 1, systemId).andExpect(status().isOk());

        JsonNode inputProblem = currentInput().path("problems").get(0);
        assertThat(inputProblem.path("summary").asString()).isEqualTo("화면이 열리지 않음");
        assertThat(inputProblem.path("standardRequest").path("summary").asString()).isEqualTo("화면이 열리지 않음");
        assertThat(inputProblem.path("standardRequest").path("details").path("actualBehavior").asString()).isEqualTo("화면 오류");
        assertThat(inputProblem.path("reportedSystemId").asString()).isEqualTo(systemId.toString());
        assertThat(inputProblem.path("reportedSystemCorrectionMessageId").asString()).isEqualTo(firstMessageId());
    }

    @Test
    void 선택한_시스템은_문의_상세에_보고_시스템과_분석_중으로_보인다() throws Exception {
        String firstRun = succeedFirstRun();
        UUID problem = waitingSystemProblem(firstRun, 2);

        mockMvc.perform(as(memberId, get("/api/v2/inquiries/" + inquiryId)))
                .andExpect(jsonPath("$.problems[0].state").value("WAITING_SYSTEM"))
                .andExpect(jsonPath("$.problems[0].reportedSystemId").doesNotExist());

        confirm(memberId, problem, UUID.randomUUID(), 1, systemId).andExpect(status().isOk());

        mockMvc.perform(as(memberId, get("/api/v2/inquiries/" + inquiryId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inquiryVersion").value(2))
                .andExpect(jsonPath("$.problems[0].state").value("ANALYZING"))
                .andExpect(jsonPath("$.problems[0].reportedSystemId").value(systemId.toString()))
                .andExpect(jsonPath("$.problems[0].questionCount").value(2))
                // 비어 있던 값을 채운 것이라 문제 요약은 그대로다
                .andExpect(jsonPath("$.problems[0].summary").value("화면이 열리지 않음"));
    }

    @Test
    void 분석_전에_질문_중인_시스템_미정_문제도_시스템을_고를_수_있다() throws Exception {
        String firstRun = succeedFirstRun();
        UUID problem = insertProblem(1, "CLARIFYING", 1, null, firstRun);
        insertStandardRequest(problem, firstRun, "화면 오류", null);
        linkFirstMessage(problem);

        confirm(memberId, problem, UUID.randomUUID(), 1, systemId).andExpect(status().isOk());

        assertThat(currentInput().path("problems").get(0).path("reportedSystemId").asString()).isEqualTo(systemId.toString());
        assertThat(jdbc.queryForObject("SELECT clarify_count FROM inquiry_problem WHERE id = ?", Integer.class, problem)).isEqualTo(1);
    }

    @Test
    void 같은_요청_키는_처음_결과를_돌려주고_새_실행을_만들지_않는다() throws Exception {
        String firstRun = succeedFirstRun();
        UUID problem = waitingSystemProblem(firstRun, 1);
        UUID requestId = UUID.randomUUID();

        confirm(memberId, problem, requestId, 1, systemId).andExpect(status().isOk());
        String nextRun = currentRunId();
        confirm(memberId, problem, requestId, 1, systemId).andExpect(status().isOk())
                .andExpect(jsonPath("$.resource.id").value(inquiryId));

        assertThat(currentRunId()).isEqualTo(nextRun);
        assertThat(count("SELECT count(*) FROM agent_run WHERE inquiry_id = ?::uuid")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT version FROM inquiry WHERE id = ?::uuid", Integer.class, inquiryId)).isEqualTo(2);
    }

    @Test
    void 진행_중이던_다른_문제의_분석은_새_실행에_이어_붙인다() throws Exception {
        String firstRun = succeedFirstRun();
        UUID waiting = waitingSystemProblem(firstRun, 1);
        UUID other = insertProblem(2, "CLARIFYING", 1, null, firstRun);
        insertStandardRequest(other, firstRun, "다른 문제", systemId);
        send(memberId, UUID.randomUUID(), 1, "오류 문구는 접근 거부예요", List.of(other)).andExpect(status().isOk());
        String running = currentRunId();
        assertThat(problemIdsOf(currentInput())).containsExactly(other.toString());

        confirm(memberId, waiting, UUID.randomUUID(), 2, loginSystemId).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT run_status FROM agent_run WHERE id = ?::uuid", String.class, running)).isEqualTo("CANCELLED");
        assertThat(problemIdsOf(currentInput())).containsExactlyInAnyOrder(waiting.toString(), other.toString());
    }

    @Test
    void 다른_문제의_정정_기록이_있는_원문은_건너뛰고_이전_원문에_선택을_남긴다() throws Exception {
        String firstRun = succeedFirstRun();
        UUID waiting = waitingSystemProblem(firstRun, 1);
        UUID other = insertProblem(2, "CLARIFYING", 1, null, firstRun);
        insertStandardRequest(other, firstRun, "다른 문제", systemId);
        send(memberId, UUID.randomUUID(), 1, "화면이 하얗게 나와요", List.of(waiting)).andExpect(status().isOk());
        String later = messageIdAt(2);
        succeedFirstRun();
        // 더 나중 원문이 다른 문제의 정정 기록을 이미 갖고 있다
        jdbc.update("UPDATE message SET payload = jsonb_set(payload, '{reportedSystemCorrection}', CAST(? AS jsonb)) WHERE id = ?::uuid",
                "{\"problemId\": \"" + other + "\", \"previousSystemId\": \"" + loginSystemId + "\", \"systemId\": \"" + systemId
                        + "\", \"systemName\": \"전자결재\", \"sourceMessageId\": \"" + later + "\", \"sourceRevision\": 1, "
                        + "\"inquiryVersion\": 1}", later);

        confirm(memberId, waiting, UUID.randomUUID(), 2, loginSystemId).andExpect(status().isOk());

        assertThat(payloadOf(firstMessageId()).path("reportedSystemCorrection").path("problemId").asString()).isEqualTo(waiting.toString());
        assertThat(payloadOf(later).path("reportedSystemCorrection").path("problemId").asString()).isEqualTo(other.toString());
        assertThat(currentInput().path("problems").get(0).path("reportedSystemCorrectionMessageId").asString()).isEqualTo(firstMessageId());
    }

    @Test
    void 서로_다른_키로_동시에_고르면_하나만_저장하고_나머지는_버전_충돌이다() throws Exception {
        String firstRun = succeedFirstRun();
        UUID problem = waitingSystemProblem(firstRun, 1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        List<Integer> statuses = new ArrayList<>();
        try {
            var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit(() -> confirm(memberId, problem, UUID.randomUUID(), 1, systemId).andReturn()
                        .getResponse().getStatus()));
            }
            for (var future : futures) statuses.add(future.get());
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses).containsOnlyOnce(200).filteredOn(code -> code != 200).containsOnly(409);
        assertThat(jdbc.queryForObject("SELECT version FROM inquiry WHERE id = ?::uuid", Integer.class, inquiryId)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM agent_run WHERE inquiry_id = ?::uuid")).isEqualTo(2);
    }

    // ---- 거절 ----

    @Test
    void 화면이_본_문의_버전이_다르면_409이고_아무것도_바꾸지_않는다() throws Exception {
        String firstRun = succeedFirstRun();
        UUID problem = waitingSystemProblem(firstRun, 1);

        confirm(memberId, problem, UUID.randomUUID(), 2, systemId)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INQUIRY_VERSION_CONFLICT"));

        assertUntouched(firstRun, problem, "SYSTEM_PENDING");
    }

    @Test
    void 이미_시스템이_정해진_문제는_409다() throws Exception {
        String firstRun = succeedFirstRun();
        UUID problem = insertProblem(1, "CLARIFYING", 1, null, firstRun);
        insertStandardRequest(problem, firstRun, "화면 오류", systemId);
        linkFirstMessage(problem);

        confirm(memberId, problem, UUID.randomUUID(), 1, loginSystemId)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));

        assertUntouched(firstRun, problem, "CLARIFYING");
    }

    @Test
    void 이미_시스템을_고른_문제를_다시_고르면_409다() throws Exception {
        String firstRun = succeedFirstRun();
        UUID problem = waitingSystemProblem(firstRun, 1);
        confirm(memberId, problem, UUID.randomUUID(), 1, systemId).andExpect(status().isOk());

        confirm(memberId, problem, UUID.randomUUID(), 2, loginSystemId)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));

        assertThat(jdbc.queryForObject("SELECT version FROM inquiry WHERE id = ?::uuid", Integer.class, inquiryId)).isEqualTo(2);
    }

    @Test
    void 시스템_대기가_아닌_상태의_문제는_409다() throws Exception {
        String firstRun = succeedFirstRun();
        for (String state : List.of("GUIDED", "OUT_OF_SCOPE", "RESOLVED_WITHOUT_TICKET")) {
            UUID problem = waitingSystemProblem(firstRun, 1);
            jdbc.update("UPDATE inquiry_problem SET problem_status = ?, resolved_at = CASE WHEN ? = 'RESOLVED_WITHOUT_TICKET' "
                    + "THEN now() ELSE NULL END WHERE id = ?", state, state, problem);
            confirm(memberId, problem, UUID.randomUUID(), 1, systemId)
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));
            jdbc.update("DELETE FROM standard_request WHERE inquiry_problem_id = ?", problem);
            jdbc.update("DELETE FROM inquiry_problem WHERE id = ?", problem);
        }
        assertThat(jdbc.queryForObject("SELECT version FROM inquiry WHERE id = ?::uuid", Integer.class, inquiryId)).isEqualTo(1);
    }

    @Test
    void 티켓_생성_작업이_있는_문제는_409다() throws Exception {
        String firstRun = succeedFirstRun();
        UUID problem = waitingSystemProblem(firstRun, 1);
        jdbc.update("UPDATE inquiry_problem SET creation_outcome = 'NOT_CREATED', creation_input_version = 1, "
                + "problem_status = 'TICKET_FAILED' WHERE id = ?", problem);

        confirm(memberId, problem, UUID.randomUUID(), 1, systemId)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    @Test
    void 지원하지_않는_시스템은_400이다() throws Exception {
        String firstRun = succeedFirstRun();
        UUID problem = waitingSystemProblem(firstRun, 1);
        jdbc.update("UPDATE it_system SET deleted_at = now() WHERE id = ?", loginSystemId);

        confirm(memberId, problem, UUID.randomUUID(), 1, UUID.randomUUID()).andExpect(status().isBadRequest());
        confirm(memberId, problem, UUID.randomUUID(), 1, loginSystemId).andExpect(status().isBadRequest());

        assertUntouched(firstRun, problem, "SYSTEM_PENDING");
    }

    @Test
    void 연결된_원문이_없으면_가장_나중_사용자_메시지에_선택을_남긴다() throws Exception {
        String firstRun = succeedFirstRun();
        UUID problem = insertProblem(1, "SYSTEM_PENDING", 0, null, firstRun);
        insertStandardRequest(problem, firstRun, "화면 오류", null);

        confirm(memberId, problem, UUID.randomUUID(), 1, systemId).andExpect(status().isOk());

        assertThat(payloadOf(firstMessageId()).path("reportedSystemCorrection").path("problemId").asString()).isEqualTo(problem.toString());
        assertThat(currentInput().path("problems").get(0).path("reportedSystemCorrectionMessageId").asString()).isEqualTo(firstMessageId());
    }

    @Test
    void 쓸_수_있는_사용자_메시지가_하나도_없으면_409이고_입력_버전도_되돌린다() throws Exception {
        String firstRun = succeedFirstRun();
        UUID problem = insertProblem(1, "SYSTEM_PENDING", 0, null, firstRun);
        insertStandardRequest(problem, firstRun, "화면 오류", null);
        UUID other = insertProblem(2, "CLARIFYING", 1, null, firstRun);
        insertStandardRequest(other, firstRun, "다른 문제", systemId);
        // 유일한 사용자 메시지가 다른 문제의 정정 기록을 이미 갖고 있다
        jdbc.update("UPDATE message SET payload = jsonb_set(payload, '{reportedSystemCorrection}', CAST(? AS jsonb)) WHERE id = ?::uuid",
                "{\"problemId\": \"" + other + "\", \"previousSystemId\": \"" + loginSystemId + "\", \"systemId\": \"" + systemId
                        + "\", \"systemName\": \"전자결재\", \"sourceMessageId\": \"" + firstMessageId() + "\", \"sourceRevision\": 1, "
                        + "\"inquiryVersion\": 1}", firstMessageId());

        confirm(memberId, problem, UUID.randomUUID(), 1, systemId)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));

        assertThat(jdbc.queryForObject("SELECT version FROM inquiry WHERE id = ?::uuid", Integer.class, inquiryId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM agent_run WHERE inquiry_id = ?::uuid")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT problem_status FROM inquiry_problem WHERE id = ?", String.class, problem))
                .isEqualTo("SYSTEM_PENDING");
    }

    @Test
    void 끝난_문의는_409다() throws Exception {
        String firstRun = succeedFirstRun();
        UUID problem = waitingSystemProblem(firstRun, 1);
        jdbc.update("UPDATE inquiry SET inquiry_status = 'CLOSED' WHERE id = ?::uuid", inquiryId);

        confirm(memberId, problem, UUID.randomUUID(), 1, systemId)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    @Test
    void 남의_문의와_없는_문제와_다른_문의의_문제는_404다() throws Exception {
        String firstRun = succeedFirstRun();
        UUID problem = waitingSystemProblem(firstRun, 1);

        confirm(otherMemberId, problem, UUID.randomUUID(), 1, systemId).andExpect(status().isNotFound());
        confirm(memberId, UUID.randomUUID(), UUID.randomUUID(), 1, systemId).andExpect(status().isNotFound());
        UUID otherRequest = UUID.randomUUID();
        mockMvc.perform(as(otherMemberId, post("/api/v2/inquiries"))
                        .header("Idempotency-Key", otherRequest.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("requestId", otherRequest, "content", "다른 문의"))))
                .andExpect(status().isOk());
        String otherInquiry = jdbc.queryForObject("SELECT id FROM inquiry WHERE requester_member_id = ?", UUID.class,
                otherMemberId).toString();
        confirm(otherMemberId, otherInquiry, problem, UUID.randomUUID(), 1, systemId).andExpect(status().isNotFound());

        assertUntouched(firstRun, problem, "SYSTEM_PENDING");
    }

    @Test
    void 같은_키를_다른_본문에_쓰면_409다() throws Exception {
        String firstRun = succeedFirstRun();
        UUID problem = waitingSystemProblem(firstRun, 1);
        UUID requestId = UUID.randomUUID();
        confirm(memberId, problem, requestId, 1, systemId).andExpect(status().isOk());

        confirm(memberId, problem, requestId, 1, loginSystemId)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void 본문이_잘못되면_400이다() throws Exception {
        String firstRun = succeedFirstRun();
        UUID problem = waitingSystemProblem(firstRun, 1);
        UUID key = UUID.randomUUID();

        postRaw(memberId, problem, key, Map.of("expectedInquiryVersion", 1, "systemId", systemId)).andExpect(status().isBadRequest());
        postRaw(memberId, problem, key, Map.of("requestId", key, "systemId", systemId)).andExpect(status().isBadRequest());
        postRaw(memberId, problem, key, Map.of("requestId", key, "expectedInquiryVersion", 1)).andExpect(status().isBadRequest());
        postRaw(memberId, problem, key, Map.of("requestId", key, "expectedInquiryVersion", 0, "systemId", systemId))
                .andExpect(status().isBadRequest());
        postRaw(memberId, problem, key, Map.of("requestId", UUID.randomUUID(), "expectedInquiryVersion", 1, "systemId", systemId))
                .andExpect(status().isBadRequest());

        assertUntouched(firstRun, problem, "SYSTEM_PENDING");
    }

    @Test
    void 로그인하지_않으면_401이다() throws Exception {
        UUID key = UUID.randomUUID();
        mockMvc.perform(post("/api/v2/inquiries/" + inquiryId + "/problems/" + UUID.randomUUID() + "/system-confirmations")
                        .header("Idempotency-Key", key.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("requestId", key, "expectedInquiryVersion", 1, "systemId", systemId))))
                .andExpect(status().isUnauthorized());
    }

    // ---- 준비·호출 ----

    private ResultActions confirm(UUID actorId, UUID problemId, UUID requestId, int expectedInquiryVersion, UUID system)
            throws Exception {
        return confirm(actorId, inquiryId, problemId, requestId, expectedInquiryVersion, system);
    }

    private ResultActions confirm(UUID actorId, String inquiry, UUID problemId, UUID requestId, int expectedInquiryVersion,
            UUID system) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedInquiryVersion", expectedInquiryVersion);
        body.put("systemId", system);
        return mockMvc.perform(as(actorId, post("/api/v2/inquiries/" + inquiry + "/problems/" + problemId + "/system-confirmations"))
                .header("Idempotency-Key", requestId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body)));
    }

    private ResultActions postRaw(UUID actorId, UUID problemId, UUID key, Map<String, Object> body) throws Exception {
        return mockMvc.perform(as(actorId, post("/api/v2/inquiries/" + inquiryId + "/problems/" + problemId + "/system-confirmations"))
                .header("Idempotency-Key", key.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body)));
    }

    private ResultActions send(UUID actorId, UUID requestId, int expectedInquiryVersion, String content, List<UUID> problemIds)
            throws Exception {
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

    /** 첫 분석이 끝나고 보고 시스템을 정하지 못해 시스템 선택을 기다리는 문제. 첫 원문이 그 문제에 연결돼 있다. */
    private UUID waitingSystemProblem(String runId, int clarifyCount) {
        UUID problem = insertProblem(nextProblemNo(), "SYSTEM_PENDING", clarifyCount, null, runId);
        insertStandardRequest(problem, runId, "화면이 열리지 않음", null);
        linkFirstMessage(problem);
        return problem;
    }

    private int nextProblemNo() {
        return jdbc.queryForObject("SELECT COALESCE(max(problem_no), 0) + 1 FROM inquiry_problem WHERE inquiry_id = ?::uuid",
                Integer.class, inquiryId);
    }

    private void linkFirstMessage(UUID problem) {
        jdbc.update("UPDATE message SET payload = jsonb_set(payload, '{problemLinks}', COALESCE(payload -> 'problemLinks', '[]'::jsonb) "
                + "|| CAST(? AS jsonb)) WHERE id = ?::uuid",
                "[{\"problemId\": \"" + problem + "\", \"revision\": 1, \"spans\": [{\"start\": 0, \"end\": 9}]}]", firstMessageId());
    }

    private void assertUntouched(String runId, UUID problem, String problemStatus) {
        assertThat(jdbc.queryForObject("SELECT version FROM inquiry WHERE id = ?::uuid", Integer.class, inquiryId)).isEqualTo(1);
        assertThat(currentRunId()).isEqualTo(runId);
        assertThat(count("SELECT count(*) FROM agent_run WHERE inquiry_id = ?::uuid")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT problem_status FROM inquiry_problem WHERE id = ?", String.class, problem))
                .isEqualTo(problemStatus);
        assertThat(payloadOf(firstMessageId()).has("reportedSystemCorrection")).isFalse();
    }

    private UUID insertProblem(int problemNo, String status, int clarifyCount, String creationOutcome, String runId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO inquiry_problem (id, inquiry_id, problem_no, title, problem_status, clarify_count, "
                + "creation_outcome, creation_input_version, agent_run_id) VALUES (?, ?::uuid, ?, ?, ?, ?, ?, ?, ?::uuid)",
                id, inquiryId, problemNo, "문제 " + problemNo, status, clarifyCount, creationOutcome,
                creationOutcome == null ? null : 1, runId);
        return id;
    }

    private UUID insertStandardRequest(UUID problemId, String runId, String summary, UUID reportedSystem) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO standard_request (id, inquiry_problem_id, inquiry_version, agent_run_id, intent, work_type, "
                + "reported_system_id, summary, confirmed_facts, missing_items, type_detail) VALUES (?, ?, 1, ?::uuid, "
                + "'OBSERVED_SYMPTOM', 'INVESTIGATION', ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb))",
                id, problemId, runId, reportedSystem, summary, "[]", "[\"시스템\"]", "{\"actualBehavior\": \"화면 오류\"}");
        return id;
    }

    private String succeedFirstRun() {
        String runId = currentRunId();
        jdbc.update("UPDATE agent_run SET run_status = 'SUCCEEDED', started_at = now(), completed_at = now() WHERE id = ?::uuid", runId);
        return runId;
    }

    private String messageIdAt(int sequenceNo) {
        return jdbc.queryForObject("SELECT id FROM message WHERE inquiry_id = ?::uuid AND sequence_no = ?", UUID.class,
                inquiryId, sequenceNo).toString();
    }

    private String firstMessageId() {
        return jdbc.queryForObject("SELECT id FROM message WHERE inquiry_id = ?::uuid AND sequence_no = 1", UUID.class,
                inquiryId).toString();
    }

    private JsonNode payloadOf(String messageId) {
        return jsonMapper.readTree(jdbc.queryForObject("SELECT payload FROM message WHERE id = ?::uuid", String.class, messageId));
    }

    private JsonNode currentInput() {
        return jsonMapper.readTree(jdbc.queryForObject("SELECT input_snapshot FROM agent_run WHERE id = ?::uuid",
                String.class, currentRunId()));
    }

    private String currentRunId() {
        return jdbc.queryForObject("SELECT current_run_id FROM inquiry WHERE id = ?::uuid", UUID.class, inquiryId).toString();
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class, inquiryId);
    }

    private static List<String> problemIdsOf(JsonNode input) {
        List<String> ids = new ArrayList<>();
        input.path("problemIds").forEach(id -> ids.add(id.asString()));
        return ids;
    }

    private static MockHttpServletRequestBuilder as(UUID actorId, MockHttpServletRequestBuilder request) {
        return request.with(jwt().jwt(token -> token.subject(actorId.toString())));
    }
}
