package com.hihops.hops.inquiry.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * 티켓 생성 작업 결과 조회 (WIRE_CONTRACT 12.2 GET /work-items/{workItemId}, 원 문의 소유자, TicketCreationResult).
 * workItemId = problemId이고 값은 inquiry_problem의 creation_outcome·creation_input_version·ticket_error_code에서 읽는다.
 * 문의 상세(creationResult)와 같은 변환을 쓴다. 넣은 행은 테스트가 끝나면 되돌려진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"db", "test"})
@Transactional
class WorkItemControllerTest {

    private static final UUID DEPARTMENT_ID = UUID.fromString("d0000000-0000-7000-8000-000000000199");
    private static final UUID MEMBER_ID = UUID.fromString("a0000000-0000-7000-8000-000000000199");
    private static final UUID OTHER_MEMBER_ID = UUID.fromString("a0000000-0000-7000-8000-000000000198");
    private static final UUID SYSTEM_ID = UUID.fromString("3320ce84-c47c-592c-b599-6e968e12b0a3");
    private static final UUID INQUIRY_ID = UUID.fromString("10000000-0000-7000-8000-000000000101");
    private static final UUID OTHER_INQUIRY_ID = UUID.fromString("10000000-0000-7000-8000-000000000102");
    private static final UUID DELETED_INQUIRY_ID = UUID.fromString("10000000-0000-7000-8000-000000000103");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void 테스트용_문의를_넣는다() {
        jdbc.update("INSERT INTO department (id, code, name) VALUES (?, 'TEST_WI', '테스트 팀')", DEPARTMENT_ID);
        insertMember(MEMBER_ID, "test.wi.requester", "29990199");
        insertMember(OTHER_MEMBER_ID, "test.wi.other", "29990198");
        jdbc.update("INSERT INTO it_system (id, code, name, primary_owner_member_id) VALUES (?, 'ELECTRONIC_APPROVAL', '전자결재', ?)",
                SYSTEM_ID, OTHER_MEMBER_ID);
        insertInquiry(INQUIRY_ID, "Q-9101", MEMBER_ID, false);
        insertInquiry(OTHER_INQUIRY_ID, "Q-9102", OTHER_MEMBER_ID, false);
        insertInquiry(DELETED_INQUIRY_ID, "Q-9103", MEMBER_ID, true);
    }

    @Test
    void 저장된_작업은_티켓_id를_돌려준다() throws Exception {
        UUID problem = problem(INQUIRY_ID, 1, "TICKET_CREATED", "STORED", 3, null, false);
        UUID ticket = insertTicket(INQUIRY_ID, problem, "TK-9201", false);

        mockMvc.perform(asMember(get("/api/v2/work-items/" + problem)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workItemId").value(problem.toString()))
                .andExpect(jsonPath("$.problemId").value(problem.toString()))
                .andExpect(jsonPath("$.inquiryVersion").value(3))
                .andExpect(jsonPath("$.outcome").value("STORED"))
                .andExpect(jsonPath("$.ticketId").value(ticket.toString()))
                .andExpect(jsonPath("$.error").isEmpty())
                .andExpect(jsonPath("$.updatedAt").isString());
    }

    @Test
    void 미생성은_오류와_함께_티켓_없이_돌려준다() throws Exception {
        UUID problem = problem(INQUIRY_ID, 1, "TICKET_FAILED", "NOT_CREATED", 2, "ASSIGNMENT_UNAVAILABLE", false);

        mockMvc.perform(asMember(get("/api/v2/work-items/" + problem)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("NOT_CREATED"))
                .andExpect(jsonPath("$.inquiryVersion").value(2))
                .andExpect(jsonPath("$.ticketId").isEmpty())
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.requestId").isString());
    }

    @Test
    void 저장_여부를_모르는_작업은_UNKNOWN과_PENDING으로_구별해_돌려준다() throws Exception {
        UUID unknown = problem(INQUIRY_ID, 1, "TICKET_FAILED", "UNKNOWN", 1, null, false);
        UUID pending = problem(INQUIRY_ID, 2, "TICKET_FAILED", "PENDING", 1, null, false);

        mockMvc.perform(asMember(get("/api/v2/work-items/" + unknown)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("UNKNOWN"))
                .andExpect(jsonPath("$.ticketId").isEmpty())
                .andExpect(jsonPath("$.error").isEmpty());
        mockMvc.perform(asMember(get("/api/v2/work-items/" + pending)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("PENDING"))
                .andExpect(jsonPath("$.ticketId").isEmpty());
    }

    @Test
    void 작업이_없는_문제는_404다() throws Exception {
        UUID problem = problem(INQUIRY_ID, 1, "CLARIFYING", null, null, null, false);
        mockMvc.perform(asMember(get("/api/v2/work-items/" + problem)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void 다른_사람의_문의나_삭제된_문제와_문의_없는_id는_404다() throws Exception {
        UUID others = problem(OTHER_INQUIRY_ID, 1, "TICKET_FAILED", "NOT_CREATED", 1, null, false);
        UUID deletedProblem = problem(INQUIRY_ID, 1, "TICKET_FAILED", "NOT_CREATED", 1, null, true);
        UUID inDeletedInquiry = problem(DELETED_INQUIRY_ID, 1, "TICKET_FAILED", "NOT_CREATED", 1, null, false);
        for (UUID id : new UUID[] {others, deletedProblem, inDeletedInquiry, UUID.randomUUID()}) {
            mockMvc.perform(asMember(get("/api/v2/work-items/" + id)))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
    }

    @Test
    void 로그인하지_않으면_401이다() throws Exception {
        UUID problem = problem(INQUIRY_ID, 1, "TICKET_FAILED", "NOT_CREATED", 1, null, false);
        mockMvc.perform(get("/api/v2/work-items/" + problem))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    // 아래 복구 규칙은 내부 AI 조회(TicketCreationService.result)와 같다:
    // 삭제되지 않은 티켓에 현재 배정이 있으면 STORED, 티켓 행이 있어도 그 외(삭제·배정 없음·배정 종료)면 UNKNOWN,
    // 티켓 행이 없으면 저장된 값(STORED는 UNKNOWN). 정상 경로는 티켓·배정·STORED를 한 거래로 쓰므로 이 분기는 수동 수정·부분 삭제 같은
    // 데이터 손상 때만 탄다. 삭제된 티켓 행도 uk_ticket_inquiry_problem을 계속 차지해 재시도할 수 없다.

    @Test
    void 삭제되지_않은_티켓에_현재_배정이_있으면_저장된_값이_달라도_STORED로_복구한다() throws Exception {
        int no = 0;
        for (String outcome : new String[] {"UNKNOWN", "PENDING", "NOT_CREATED"}) {
            UUID problem = problem(INQUIRY_ID, ++no, "TICKET_FAILED", outcome, 1, null, false);
            UUID ticket = insertTicket(INQUIRY_ID, problem, "TK-" + outcome, false);
            mockMvc.perform(asMember(get("/api/v2/work-items/" + problem)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("STORED"))
                    .andExpect(jsonPath("$.ticketId").value(ticket.toString()))
                    .andExpect(jsonPath("$.error").isEmpty());
        }
    }

    @Test
    void 티켓이_삭제됐거나_현재_배정이_없으면_저장된_값과_상관없이_UNKNOWN이다() throws Exception {
        int no = 0;
        for (String outcome : new String[] {"STORED", "NOT_CREATED", "PENDING", "UNKNOWN"}) {
            for (Assignment assignment : Assignment.values()) {
                for (boolean deleted : new boolean[] {false, true}) {
                    if (!deleted && assignment == Assignment.ACTIVE) continue; // 정상 STORED
                    UUID problem = problem(INQUIRY_ID, ++no, "TICKET_FAILED", outcome, 1, null, false);
                    insertTicket(INQUIRY_ID, problem, "TK-" + no, deleted, assignment);
                    mockMvc.perform(asMember(get("/api/v2/work-items/" + problem)))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.outcome").value("UNKNOWN"))
                            .andExpect(jsonPath("$.ticketId").isEmpty())
                            .andExpect(jsonPath("$.error").isEmpty());
                }
            }
        }
    }

    @Test
    void 티켓_행이_없는_STORED는_UNKNOWN이고_다른_값은_그대로다() throws Exception {
        UUID stored = problem(INQUIRY_ID, 1, "TICKET_CREATED", "STORED", 1, null, false);
        UUID notCreated = problem(INQUIRY_ID, 2, "TICKET_FAILED", "NOT_CREATED", 1, "ASSIGNMENT_UNAVAILABLE", false);
        mockMvc.perform(asMember(get("/api/v2/work-items/" + stored)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("UNKNOWN"))
                .andExpect(jsonPath("$.ticketId").isEmpty());
        mockMvc.perform(asMember(get("/api/v2/work-items/" + notCreated)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("NOT_CREATED"));
    }

    @Test
    void 알_수_없는_저장_오류_코드는_공개_코드로_바꾸고_내부_문구를_내보내지_않는다() throws Exception {
        UUID problem = problem(INQUIRY_ID, 1, "TICKET_FAILED", "NOT_CREATED", 1, "INTERNAL_DB_TIMEOUT_ON_SHARD_7", false);
        mockMvc.perform(asMember(get("/api/v2/work-items/" + problem)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(content().string(not(containsString("SHARD_7"))));
    }

    @Test
    void 입력_버전이_없는_저장_규칙_위반은_내부_문구_없이_500이다() throws Exception {
        UUID problem = problem(INQUIRY_ID, 1, "TICKET_FAILED", "NOT_CREATED", null, null, false);
        mockMvc.perform(asMember(get("/api/v2/work-items/" + problem)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.requestId").isString())
                .andExpect(content().string(not(containsString("티켓 생성 결과"))));
    }

    private static MockHttpServletRequestBuilder asMember(MockHttpServletRequestBuilder request) {
        return request.with(jwt().jwt(token -> token.subject(MEMBER_ID.toString())));
    }

    private void insertMember(UUID id, String loginId, String employeeNo) {
        jdbc.update("INSERT INTO member (id, login_id, employee_no, password_hash, name, department_id) "
                + "VALUES (?, ?, ?, 'hash', '테스트 회원', ?)", id, loginId, employeeNo, DEPARTMENT_ID);
    }

    private void insertInquiry(UUID id, String number, UUID requesterId, boolean deleted) {
        jdbc.update("INSERT INTO inquiry (id, inquiry_number, requester_member_id, title, inquiry_status, created_at, updated_at, deleted_at) "
                + "VALUES (?, ?, ?, ?, 'IN_PROGRESS', now(), now(), " + (deleted ? "now()" : "NULL") + ")",
                id, number, requesterId, "문의 " + number);
    }

    private UUID problem(UUID inquiryId, int no, String status, String outcome, Integer version, String errorCode,
            boolean deleted) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO inquiry_problem (id, inquiry_id, problem_no, title, problem_status, creation_outcome, "
                + "creation_input_version, ticket_error_code, deleted_at) VALUES (?, ?, ?, '문제', ?, ?, ?, ?, "
                + (deleted ? "now()" : "NULL") + ")", id, inquiryId, no, status, outcome, version, errorCode);
        return id;
    }

    // 티켓은 표준 요청서·분석 실행을 거쳐야 만들 수 있어 함께 넣는다
    private UUID insertTicket(UUID inquiryId, UUID problemId, String number, boolean deleted) {
        return insertTicket(inquiryId, problemId, number, deleted, Assignment.ACTIVE);
    }

    private enum Assignment { ACTIVE, ENDED, NONE }

    private UUID insertTicket(UUID inquiryId, UUID problemId, String number, boolean deleted, Assignment assignment) {
        UUID run = UUID.randomUUID();
        jdbc.update("INSERT INTO agent_run (id, inquiry_id, task, run_status, input_version, input_snapshot, model_version, "
                + "prompt_version, schema_version, started_at, completed_at) VALUES (?, ?, 'INQUIRY_ANALYSIS', 'SUCCEEDED', 1, "
                + "'{}', 'gpt-test', 'prompt-v1', 'schema-v1', now(), now())", run, inquiryId);
        UUID request = UUID.randomUUID();
        jdbc.update("INSERT INTO standard_request (id, inquiry_problem_id, inquiry_version, agent_run_id, intent, work_type, "
                + "reported_system_id, responsible_system_id, summary) VALUES (?, ?, 1, ?, 'OBSERVED_SYMPTOM', 'INVESTIGATION', ?, ?, '요약')",
                request, problemId, run, SYSTEM_ID, SYSTEM_ID);
        UUID ticket = UUID.randomUUID();
        jdbc.update("INSERT INTO ticket (id, ticket_number, inquiry_id, inquiry_problem_id, standard_request_id, title, detail, "
                + "work_type, ticket_status, responsible_system_id, assignee_member_id) VALUES (?, ?, ?, ?, ?, '티켓', "
                + "'내용', 'INVESTIGATION', 'OPEN', ?, ?)", ticket, number, inquiryId, problemId, request, SYSTEM_ID, OTHER_MEMBER_ID);
        if (assignment != Assignment.NONE) {
            // 정상 경로는 티켓과 같은 거래로 현재 배정(ended_at 없음)을 함께 넣는다
            jdbc.update("INSERT INTO ticket_assignment (ticket_id, it_system_id, assignee_member_id, source, reason, evidence, "
                    + "assigned_at, ended_at) VALUES (?, ?, ?, 'AUTO', '자동 배정', '{}'::jsonb, now(), "
                    + (assignment == Assignment.ENDED ? "now()" : "NULL") + ")", ticket, SYSTEM_ID, OTHER_MEMBER_ID);
        }
        if (deleted) jdbc.update("UPDATE ticket SET deleted_at = now() WHERE id = ?", ticket);
        return ticket;
    }
}
