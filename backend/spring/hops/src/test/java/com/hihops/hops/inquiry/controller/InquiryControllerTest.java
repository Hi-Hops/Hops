package com.hihops.hops.inquiry.controller;

import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * 문의자 조회 API(GET /api/v2/inquiries, /{id})가 본인 문의만, 계약 형식으로 돌려주는지 확인한다
 * (WIRE_CONTRACT 12.2, DATA_TYPES 신규 절). 넣은 행은 테스트가 끝나면 되돌려진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"db", "test"})
@Transactional
class InquiryControllerTest {

    private static final UUID DEPARTMENT_ID = UUID.fromString("d0000000-0000-7000-8000-000000000099");
    private static final UUID MEMBER_ID = UUID.fromString("a0000000-0000-7000-8000-000000000099");
    private static final UUID OTHER_MEMBER_ID = UUID.fromString("a0000000-0000-7000-8000-000000000098");
    private static final UUID SYSTEM_ID = UUID.fromString("3320ce84-c47c-592c-b599-6e968e12b0a3");

    private static final UUID OLD_INQUIRY_ID = UUID.fromString("10000000-0000-7000-8000-000000000001");
    private static final UUID NEW_INQUIRY_ID = UUID.fromString("10000000-0000-7000-8000-000000000002");
    private static final UUID DELETED_INQUIRY_ID = UUID.fromString("10000000-0000-7000-8000-000000000003");
    private static final UUID OTHER_INQUIRY_ID = UUID.fromString("10000000-0000-7000-8000-000000000004");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 테스트용_문의를_넣는다() {
        jdbcTemplate.update("INSERT INTO department (id, code, name) VALUES (?, 'TEST_SALES', '테스트 영업팀')", DEPARTMENT_ID);
        insertMember(MEMBER_ID, "test.requester", "29990099");
        insertMember(OTHER_MEMBER_ID, "test.other", "29990098");
        jdbcTemplate.update("INSERT INTO it_system (id, code, name, primary_owner_member_id) "
                + "VALUES (?, 'ELECTRONIC_APPROVAL', '전자결재', ?)", SYSTEM_ID, OTHER_MEMBER_ID);
        // 수정 시각을 직접 정해 목록 순서를 고정한다 (트랜잭션 안의 now()는 모두 같은 시각이다)
        insertInquiry(OLD_INQUIRY_ID, "Q-9001", MEMBER_ID, "now() - interval '2 hours'", false);
        insertInquiry(NEW_INQUIRY_ID, "Q-9002", MEMBER_ID, "now() - interval '1 hour'", false);
        insertInquiry(DELETED_INQUIRY_ID, "Q-9003", MEMBER_ID, "now()", true);
        insertInquiry(OTHER_INQUIRY_ID, "Q-9004", OTHER_MEMBER_ID, "now()", false);
    }

    @Test
    void 내_문의만_최근_수정_순으로_돌려준다() throws Exception {
        mockMvc.perform(asMember(get("/api/v2/inquiries")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id").value(contains(NEW_INQUIRY_ID.toString(), OLD_INQUIRY_ID.toString())))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].inquiryNumber").value("Q-9002"))
                .andExpect(jsonPath("$.items[0].title").value("문의 Q-9002"))
                .andExpect(jsonPath("$.items[0].status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.items[0].problemCount").value(0))
                .andExpect(jsonPath("$.items[0].createdAt").isString())
                .andExpect(jsonPath("$.items[0].updatedAt").isString());

        mockMvc.perform(asMember(get("/api/v2/inquiries").param("page", "1").param("size", "1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id").value(contains(OLD_INQUIRY_ID.toString())))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void 목록에_문제_티켓_확인_대기_수를_센다() throws Exception {
        UUID runId = insertRun(NEW_INQUIRY_ID);
        insertProblem(NEW_INQUIRY_ID, 1, "GUIDED", false); // 가이드 안내 뒤 확인 대기
        UUID waiting = insertProblem(NEW_INQUIRY_ID, 2, "TICKET_CREATED", false);
        insertTicket(NEW_INQUIRY_ID, waiting, runId, "TK-9001", "WAITING_CONFIRM"); // 티켓 확인 대기
        UUID working = insertProblem(NEW_INQUIRY_ID, 3, "TICKET_CREATED", false);
        insertTicket(NEW_INQUIRY_ID, working, runId, "TK-9002", "IN_PROGRESS");
        insertProblem(NEW_INQUIRY_ID, 4, "CLARIFYING", false);
        insertProblem(NEW_INQUIRY_ID, 5, "CLARIFYING", true); // 삭제된 문제는 세지 않는다

        mockMvc.perform(asMember(get("/api/v2/inquiries")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(NEW_INQUIRY_ID.toString()))
                .andExpect(jsonPath("$.items[0].problemCount").value(4))
                .andExpect(jsonPath("$.items[0].ticketCount").value(2))
                .andExpect(jsonPath("$.items[0].pendingConfirmationCount").value(2))
                // 문제가 없는 문의는 모두 0
                .andExpect(jsonPath("$.items[1].problemCount").value(0))
                .andExpect(jsonPath("$.items[1].ticketCount").value(0))
                .andExpect(jsonPath("$.items[1].pendingConfirmationCount").value(0));
    }

    @Test
    void 페이지_범위를_벗어나면_고치지_않고_400이다() throws Exception {
        String[][] invalid = {{"size", "0"}, {"size", "101"}, {"page", "-1"}, {"size", "many"}};
        for (String[] param : invalid) {
            mockMvc.perform(asMember(get("/api/v2/inquiries").param(param[0], param[1])))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value(param[0]));
        }
    }

    @Test
    void 문의_상세를_돌려준다() throws Exception {
        insertMessage(NEW_INQUIRY_ID, 1, "USER", "결재 문서가 안 열려요", "{}", "COMPLETED");
        UUID pending = insertMessage(NEW_INQUIRY_ID, 2, "USER", "그리고 첨부도요", "{\"pendingTarget\": true}", "COMPLETED");

        mockMvc.perform(asMember(get("/api/v2/inquiries/" + NEW_INQUIRY_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(NEW_INQUIRY_ID.toString()))
                .andExpect(jsonPath("$.inquiryNumber").value("Q-9002"))
                .andExpect(jsonPath("$.inquiryVersion").value(1))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.problems").isArray())
                .andExpect(jsonPath("$.problems").isEmpty())
                // 계약상 null 값도 키를 생략하지 않는다
                .andExpect(jsonPath("$.currentRun").hasJsonPath())
                .andExpect(jsonPath("$.currentRun").isEmpty())
                .andExpect(jsonPath("$.pendingTargetMessageIds").value(contains(pending.toString())))
                .andExpect(jsonPath("$.resultRevision").value(1))
                .andExpect(jsonPath("$.createdAt").isString())
                .andExpect(jsonPath("$.updatedAt").isString());
    }

    @Test
    void 남의_문의_삭제된_문의_없는_문의는_모두_404다() throws Exception {
        for (UUID id : new UUID[] {OTHER_INQUIRY_ID, DELETED_INQUIRY_ID, UUID.randomUUID()}) {
            mockMvc.perform(asMember(get("/api/v2/inquiries/" + id)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
    }

    @Test
    void 문제가_있는_문의는_빈_목록으로_숨기지_않고_실패한다() throws Exception {
        insertProblem(NEW_INQUIRY_ID, 1, "CLARIFYING", false);

        mockMvc.perform(asMember(get("/api/v2/inquiries/" + NEW_INQUIRY_ID)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @Test
    void 로그인하지_않으면_401이다() throws Exception {
        for (String path : new String[] {"/api/v2/inquiries", "/api/v2/inquiries/" + NEW_INQUIRY_ID}) {
            mockMvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
    }

    private static MockHttpServletRequestBuilder asMember(MockHttpServletRequestBuilder request) {
        return request.with(jwt().jwt(token -> token.subject(MEMBER_ID.toString())));
    }

    private void insertMember(UUID id, String loginId, String employeeNo) {
        jdbcTemplate.update("INSERT INTO member (id, login_id, employee_no, password_hash, name, department_id) "
                + "VALUES (?, ?, ?, 'hash', '테스트 회원', ?)", id, loginId, employeeNo, DEPARTMENT_ID);
    }

    private void insertInquiry(UUID id, String number, UUID requesterId, String updatedAt, boolean deleted) {
        jdbcTemplate.update("INSERT INTO inquiry (id, inquiry_number, requester_member_id, title, inquiry_status, "
                + "created_at, updated_at, deleted_at) VALUES (?, ?, ?, ?, 'IN_PROGRESS', now() - interval '3 hours', "
                + updatedAt + ", " + (deleted ? "now()" : "NULL") + ")", id, number, requesterId, "문의 " + number);
    }

    private UUID insertProblem(UUID inquiryId, int problemNo, String status, boolean deleted) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO inquiry_problem (id, inquiry_id, problem_no, title, problem_status, deleted_at) "
                + "VALUES (?, ?, ?, '문제', ?, " + (deleted ? "now()" : "NULL") + ")", id, inquiryId, problemNo, status);
        return id;
    }

    private UUID insertRun(UUID inquiryId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO agent_run (id, inquiry_id, task, run_status, input_version, input_snapshot, "
                + "model_version, prompt_version, schema_version, started_at, completed_at) "
                + "VALUES (?, ?, 'INQUIRY_ANALYSIS', 'SUCCEEDED', 1, '{}', 'gpt-test', 'prompt-v1', 'schema-v1', now(), now())",
                id, inquiryId);
        return id;
    }

    // 티켓은 표준 요청서를 거쳐야 만들 수 있어 둘을 함께 넣는다
    private void insertTicket(UUID inquiryId, UUID problemId, UUID runId, String number, String status) {
        UUID requestId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO standard_request (id, inquiry_problem_id, inquiry_version, agent_run_id, intent, "
                + "work_type, responsible_system_id, summary) VALUES (?, ?, 1, ?, 'OBSERVED_SYMPTOM', 'INVESTIGATION', ?, '요약')",
                requestId, problemId, runId, SYSTEM_ID);
        jdbcTemplate.update("INSERT INTO ticket (ticket_number, inquiry_id, inquiry_problem_id, standard_request_id, "
                + "title, detail, work_type, ticket_status, responsible_system_id, assignee_member_id) "
                + "VALUES (?, ?, ?, ?, '티켓', '내용', 'INVESTIGATION', ?, ?, ?)",
                number, inquiryId, problemId, requestId, status, SYSTEM_ID, OTHER_MEMBER_ID);
    }

    private UUID insertMessage(UUID inquiryId, int sequenceNo, String sender, String content, String payload,
            String status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO message (id, inquiry_id, sequence_no, sender_type, content, payload, message_status) "
                + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)", id, inquiryId, sequenceNo, sender, content, payload, status);
        return id;
    }
}
