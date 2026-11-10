package com.hihops.hops.inquiry.controller;

import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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

import jakarta.persistence.EntityManager;

/**
 * 문의자 조회 API(GET /api/v2/inquiries, /{id}, /{id}/messages)가 본인 문의만, 계약 형식으로 돌려주는지 확인한다
 * (WIRE_CONTRACT 12.2·12.8, DATA_TYPES 신규 절). 넣은 행은 테스트가 끝나면 되돌려진다.
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

    private static final UUID REQUEST_ID = UUID.fromString("20000000-0000-7000-8000-000000000001");
    private static final UUID PROBLEM_A = UUID.fromString("30000000-0000-7000-8000-00000000000a");
    private static final UUID PROBLEM_B = UUID.fromString("30000000-0000-7000-8000-00000000000b");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 테스트 하나가 한 트랜잭션이라, JDBC로 바꾼 행을 다시 조회하려면 JPA 1차 캐시를 비워야 한다
    @Autowired
    private EntityManager entityManager;

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
                .andExpect(jsonPath("$.items[0].title").value("새 문의"))
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
    void 상태로_본인_문의만_거른_뒤_페이지를_나눈다() throws Exception {
        jdbcTemplate.update("UPDATE inquiry SET inquiry_status='RESOLVED' WHERE id IN (?, ?, ?)",
                OLD_INQUIRY_ID, DELETED_INQUIRY_ID, OTHER_INQUIRY_ID);

        mockMvc.perform(asMember(get("/api/v2/inquiries").param("status", "RESOLVED").param("size", "1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id").value(contains(OLD_INQUIRY_ID.toString())))
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(asMember(get("/api/v2/inquiries").param("status", "CLOSED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(asMember(get("/api/v2/inquiries").param("status", "UNKNOWN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("status"));
    }

    @Test
    void 대표_제목은_첫_지원_문제에서_가져오고_범위_밖_문제는_세지_않는다() throws Exception {
        jdbcTemplate.update("UPDATE inquiry SET title='안녕' WHERE id=?", NEW_INQUIRY_ID);
        insertProblem(NEW_INQUIRY_ID, 1, "OUT_OF_SCOPE", false);
        UUID supported = insertProblem(NEW_INQUIRY_ID, 2, "CLARIFYING", false);
        jdbcTemplate.update("UPDATE inquiry_problem SET title='전자결재 문서함 조회' WHERE id=?", supported);
        insertProblem(NEW_INQUIRY_ID, 3, "SYSTEM_PENDING", false);
        insertProblem(OLD_INQUIRY_ID, 1, "OUT_OF_SCOPE", false);

        mockMvc.perform(asMember(get("/api/v2/inquiries")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].title").value("전자결재 문서함 조회 외 1건"))
                .andExpect(jsonPath("$.items[1].title").value("지원 범위 안내"));
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
        mockMvc.perform(asMember(get("/api/v2/inquiries/" + NEW_INQUIRY_ID + "/messages").param("beforeSequenceNo", "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("beforeSequenceNo"));
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
            mockMvc.perform(asMember(get("/api/v2/inquiries/" + id + "/messages")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
    }

    @Test
    void 문제별_결과를_최신_적용_요청서와_상태로_돌려주고_내부_요청서는_숨긴다() throws Exception {
        UUID runId = insertRun(NEW_INQUIRY_ID);
        UUID problem = problem(NEW_INQUIRY_ID, 1, "CLARIFYING", 2, null, null);
        insertRequest(problem, runId, "오래된 요약", SYSTEM_ID, 1);
        insertRequest(problem, runId, "결재 문서 조회 오류 조사", SYSTEM_ID, 2); // 같은 문제의 더 새 버전이 현재 값이다
        insertRequest(problem, insertRun(NEW_INQUIRY_ID, "FAILED"), "적용 안 된 요약", SYSTEM_ID, 3);

        mockMvc.perform(asMember(get("/api/v2/inquiries/" + NEW_INQUIRY_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.problems.length()").value(1))
                .andExpect(jsonPath("$.problems[0].id").value(problem.toString()))
                .andExpect(jsonPath("$.problems[0].inquiryId").value(NEW_INQUIRY_ID.toString()))
                .andExpect(jsonPath("$.problems[0].summary").value("결재 문서 조회 오류 조사"))
                .andExpect(jsonPath("$.problems[0].intent").value("OBSERVED_SYMPTOM"))
                .andExpect(jsonPath("$.problems[0].processingType").value("INVESTIGATION"))
                .andExpect(jsonPath("$.problems[0].reportedSystemId").value(SYSTEM_ID.toString()))
                .andExpect(jsonPath("$.problems[0].reportedFeatureId").hasJsonPath())
                // 질문을 했고 분석 중인 실행이 없으니 문의자의 답을 기다린다
                .andExpect(jsonPath("$.problems[0].state").value("WAITING_USER"))
                .andExpect(jsonPath("$.problems[0].questionCount").value(2))
                .andExpect(jsonPath("$.problems[0].guide").isEmpty())
                .andExpect(jsonPath("$.problems[0].creationResult").isEmpty())
                .andExpect(jsonPath("$.problems[0].ticket").isEmpty())
                .andExpect(jsonPath("$.problems[0].activeResolutionRequest").hasJsonPath())
                .andExpect(jsonPath("$.problems[0].activeResolutionRequest").isEmpty())
                // 담당자 내부 표준 요청서(확인 사실·추정·출처)는 문의자에게 보내지 않는다
                .andExpect(jsonPath("$.problems[0].standardRequest").doesNotExist())
                .andExpect(jsonPath("$.problems[0].confirmedFacts").doesNotExist());
    }

    @Test
    void 분석_중인_실행의_대상_문제만_분석_중이고_나머지는_답을_기다린다() throws Exception {
        UUID runId = insertRun(NEW_INQUIRY_ID);
        UUID answered = problem(NEW_INQUIRY_ID, 1, "CLARIFYING", 1, null, null);
        UUID waiting = problem(NEW_INQUIRY_ID, 2, "CLARIFYING", 1, null, null);
        UUID systemPending = problem(NEW_INQUIRY_ID, 3, "SYSTEM_PENDING", 5, null, null);
        insertRequest(answered, runId, "로그인 실패", SYSTEM_ID, 1);
        insertRequest(waiting, runId, "비밀번호 문의", SYSTEM_ID, 1);
        insertRequest(systemPending, runId, "파일 공유 오류", SYSTEM_ID, 1);
        queueRun(List.of(answered));

        mockMvc.perform(asMember(get("/api/v2/inquiries/" + NEW_INQUIRY_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.problems[0].state").value("ANALYZING"))
                .andExpect(jsonPath("$.problems[1].state").value("WAITING_USER"))
                .andExpect(jsonPath("$.problems[2].state").value("WAITING_SYSTEM"))
                .andExpect(jsonPath("$.problems[2].reportedSystemId").isEmpty())
                .andExpect(jsonPath("$.problems[2].questionCount").value(5));
    }

    @Test
    void 대상_없이_접수한_실행이면_질문_중인_문제는_모두_답을_기다린다() throws Exception {
        UUID runId = insertRun(NEW_INQUIRY_ID);
        UUID first = problem(NEW_INQUIRY_ID, 1, "CLARIFYING", 1, null, null);
        UUID second = problem(NEW_INQUIRY_ID, 2, "CLARIFYING", 2, null, null);
        insertRequest(first, runId, "로그인 실패", SYSTEM_ID, 1);
        insertRequest(second, runId, "비밀번호 문의", SYSTEM_ID, 1);
        queueRun(List.of());

        mockMvc.perform(asMember(get("/api/v2/inquiries/" + NEW_INQUIRY_ID)))
                .andExpect(jsonPath("$.problems[0].state").value("WAITING_USER"))
                .andExpect(jsonPath("$.problems[1].state").value("WAITING_USER"));
    }

    @Test
    void 티켓_없는_안내_문제는_안내_메시지의_해결_확인_요청을_돌려준다() throws Exception {
        UUID runId = insertRun(NEW_INQUIRY_ID);
        UUID guided = problem(NEW_INQUIRY_ID, 1, "GUIDED", 0, null, null);
        insertRequest(guided, runId, "작성 기간 설정 안내", SYSTEM_ID, 1);
        UUID messageId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        String pending = "{\"id\": \"" + requestId + "\", \"inquiryId\": \"" + NEW_INQUIRY_ID + "\", \"problemId\": \""
                + guided + "\", \"ticketId\": null, \"source\": \"GUIDE\", \"sourceId\": \"" + messageId + "\", "
                + "\"sourceVersion\": 1, \"requestRevision\": 1, \"state\": \"PENDING\", "
                + "\"resultSummary\": \"작성 기간을 확인해 보세요\", \"confirmationOrigin\": \"HOPS_USER\", "
                + "\"createdAt\": \"2026-11-04T00:00:00Z\", \"respondedAt\": null}";
        jdbcTemplate.update("INSERT INTO message (id, inquiry_id, sequence_no, sender_type, content, payload, message_status) "
                + "VALUES (?, ?, 1, 'AI', '작성 기간을 확인해 보세요', ?::jsonb, 'COMPLETED')", messageId, NEW_INQUIRY_ID,
                "{\"answerRunId\": \"" + runId + "\", \"guide\": {\"problemId\": \"" + guided
                        + "\", \"text\": \"작성 기간을 확인해 보세요\", \"evidenceIds\": []}, \"resolutionRequest\": " + pending + "}");

        mockMvc.perform(asMember(get("/api/v2/inquiries/" + NEW_INQUIRY_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.problems[0].ticket").isEmpty())
                .andExpect(jsonPath("$.problems[0].activeResolutionRequest.id").value(requestId.toString()))
                .andExpect(jsonPath("$.problems[0].activeResolutionRequest.source").value("GUIDE"))
                .andExpect(jsonPath("$.problems[0].activeResolutionRequest.sourceId").value(messageId.toString()))
                .andExpect(jsonPath("$.problems[0].activeResolutionRequest.ticketId").isEmpty())
                .andExpect(jsonPath("$.problems[0].activeResolutionRequest.state").value("PENDING"))
                .andExpect(jsonPath("$.problems[0].activeResolutionRequest.requestRevision").value(1));

        // 응답한 요청도 최신 요청으로 돌려준다
        String responded = pending.replace("\"requestRevision\": 1", "\"requestRevision\": 2")
                .replace("\"PENDING\"", "\"RESOLVED\"").replace("\"respondedAt\": null", "\"respondedAt\": \"2026-11-04T01:00:00Z\"");
        jdbcTemplate.update("UPDATE message SET payload = jsonb_set(payload, '{resolutionRequest}', ?::jsonb) WHERE id = ?",
                responded, messageId);
        entityManager.clear();
        mockMvc.perform(asMember(get("/api/v2/inquiries/" + NEW_INQUIRY_ID)))
                .andExpect(jsonPath("$.problems[0].activeResolutionRequest.state").value("RESOLVED"))
                .andExpect(jsonPath("$.problems[0].activeResolutionRequest.requestRevision").value(2))
                .andExpect(jsonPath("$.problems[0].activeResolutionRequest.respondedAt").isString());

        // 다른 문제를 가리키는 요청은 짐작해 붙이지 않고 실패한다
        jdbcTemplate.update("UPDATE message SET payload = jsonb_set(payload, '{resolutionRequest,problemId}', ?::jsonb) "
                + "WHERE id = ?", "\"" + UUID.randomUUID() + "\"", messageId);
        entityManager.clear();
        mockMvc.perform(asMember(get("/api/v2/inquiries/" + NEW_INQUIRY_ID)))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void 티켓_생성_결과와_티켓_요약을_돌려주고_미생성은_진행과_구분한다() throws Exception {
        UUID runId = insertRun(NEW_INQUIRY_ID);
        UUID stored = problem(NEW_INQUIRY_ID, 1, "TICKET_CREATED", 0, "STORED", null);
        UUID notCreated = problem(NEW_INQUIRY_ID, 2, "TICKET_FAILED", 0, "NOT_CREATED", "SERVICE_UNAVAILABLE");
        UUID request = insertRequest(stored, runId, "결재 문서 조회 오류 조사", SYSTEM_ID, 1);
        insertRequest(notCreated, runId, "권한 요청", SYSTEM_ID, 1);
        UUID ticketId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO ticket (id, ticket_number, inquiry_id, inquiry_problem_id, standard_request_id, "
                + "title, detail, work_type, ticket_status, responsible_system_id, assignee_member_id) "
                + "VALUES (?, 'TK-9101', ?, ?, ?, '결재 문서 조회 오류', '내용', 'INVESTIGATION', 'OPEN', ?, ?)",
                ticketId, NEW_INQUIRY_ID, stored, request, SYSTEM_ID, OTHER_MEMBER_ID);

        mockMvc.perform(asMember(get("/api/v2/inquiries/" + NEW_INQUIRY_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.problems[0].state").value("IN_SERVICE"))
                .andExpect(jsonPath("$.problems[0].creationResult.workItemId").value(stored.toString()))
                .andExpect(jsonPath("$.problems[0].creationResult.problemId").value(stored.toString()))
                .andExpect(jsonPath("$.problems[0].creationResult.inquiryVersion").value(1))
                .andExpect(jsonPath("$.problems[0].creationResult.outcome").value("STORED"))
                .andExpect(jsonPath("$.problems[0].creationResult.ticketId").value(ticketId.toString()))
                .andExpect(jsonPath("$.problems[0].creationResult.error").isEmpty())
                .andExpect(jsonPath("$.problems[0].creationResult.updatedAt").isString())
                .andExpect(jsonPath("$.problems[0].ticket.id").value(ticketId.toString()))
                .andExpect(jsonPath("$.problems[0].ticket.workItemId").value(stored.toString()))
                .andExpect(jsonPath("$.problems[0].ticket.ticketNumber").value("TK-9101"))
                .andExpect(jsonPath("$.problems[0].ticket.reportedSystem.id").value(SYSTEM_ID.toString()))
                .andExpect(jsonPath("$.problems[0].ticket.responsibleSystem.name").value("전자결재"))
                .andExpect(jsonPath("$.problems[0].ticket.assignee.id").value(OTHER_MEMBER_ID.toString()))
                .andExpect(jsonPath("$.problems[0].ticket.state").value("WAITING"))
                .andExpect(jsonPath("$.problems[0].activeResolutionRequest").isEmpty())
                // 미생성 확인은 TICKET_PENDING이고 작업 결과로 구분한다
                .andExpect(jsonPath("$.problems[1].state").value("TICKET_PENDING"))
                .andExpect(jsonPath("$.problems[1].creationResult.outcome").value("NOT_CREATED"))
                .andExpect(jsonPath("$.problems[1].creationResult.ticketId").isEmpty())
                .andExpect(jsonPath("$.problems[1].creationResult.error.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.problems[1].ticket").isEmpty());
    }

    @Test
    void 안내는_최신_안내_메시지와_지금_공개_가능한_근거만_돌려준다() throws Exception {
        UUID runId = insertRun(NEW_INQUIRY_ID);
        UUID guided = problem(NEW_INQUIRY_ID, 1, "GUIDED", 0, null, null);
        insertRequest(guided, runId, "작성 기간 설정 안내", SYSTEM_ID, 1);
        UUID evidenceId = UUID.randomUUID();
        // 실행 결과의 근거가 가리키는 지식 문서가 없으면(승인·공개 확인 불가) 근거로 내보내지 않는다
        jdbcTemplate.update("UPDATE agent_run SET result = ?::jsonb WHERE id = ?", "{\"evidence\": [{\"id\": \""
                + evidenceId + "\", \"documentId\": \"" + UUID.randomUUID() + "\", \"documentVersion\": 1, "
                + "\"kind\": \"GUIDE\", \"title\": \"가이드\", \"excerpt\": \"작성 기간\", \"systemId\": \""
                + SYSTEM_ID + "\", \"appliesToSystemVersion\": null, \"applicability\": \"공통\", "
                + "\"accessScope\": \"EMPLOYEE\", \"origin\": \"SYNTHETIC\"}]}", runId);
        String guide = "{\"answerRunId\": \"" + runId + "\", \"guide\": {\"problemId\": \"" + guided
                + "\", \"text\": \"작성 기간을 확인해 보세요\", \"evidenceIds\": [\"" + evidenceId + "\"]}}";
        insertMessage(NEW_INQUIRY_ID, 1, "AI", "예전 안내", guide, "COMPLETED");
        UUID latest = insertMessage(NEW_INQUIRY_ID, 2, "AI", "작성 기간을 확인해 보세요", guide, "COMPLETED");

        mockMvc.perform(asMember(get("/api/v2/inquiries/" + NEW_INQUIRY_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.problems[0].state").value("WAITING_CONFIRMATION"))
                .andExpect(jsonPath("$.problems[0].guide.messageId").value(latest.toString()))
                .andExpect(jsonPath("$.problems[0].guide.evidence").isArray())
                .andExpect(jsonPath("$.problems[0].guide.evidence").isEmpty());
    }

    @Test
    void 적용된_요청서가_없는_문제는_짐작해_채우지_않고_실패한다() throws Exception {
        problem(NEW_INQUIRY_ID, 1, "CLARIFYING", 1, null, null);

        mockMvc.perform(asMember(get("/api/v2/inquiries/" + NEW_INQUIRY_ID)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @Test
    void 저장된_메시지를_최신_페이지부터_오래된_순으로_돌려준다() throws Exception {
        UUID first = insertMessage(NEW_INQUIRY_ID, 1, "USER", "처음 내용", "{\"requestId\": \"" + REQUEST_ID + "\"}", "COMPLETED");
        insertMessage(NEW_INQUIRY_ID, 2, "AI", "어떤 문서인가요?", "{}", "COMPLETED");
        UUID third = insertMessage(NEW_INQUIRY_ID, 3, "USER", "고친 내용",
                "{\"problemLinks\": [{\"problemId\": \"" + PROBLEM_A + "\", \"spans\": [{\"start\": 0, \"end\": 2}]},"
                        + " {\"problemId\": \"" + PROBLEM_B + "\"}, {\"problemId\": \"" + PROBLEM_A + "\"}]}", "COMPLETED");
        // 0시에 보내고 1시·2시에 두 번 고쳤다. 이력 행의 시각은 "고친 시각"이다
        jdbcTemplate.update("UPDATE message SET revision = 3, created_at = '2026-11-01T00:00:00Z' WHERE id = ?", third);
        jdbcTemplate.update("INSERT INTO message_revision (message_id, revision_no, content, created_at) "
                + "VALUES (?, 1, '처음 원문', '2026-11-01T01:00:00Z')", third);
        jdbcTemplate.update("INSERT INTO message_revision (message_id, revision_no, content, created_at) "
                + "VALUES (?, 2, '고치기 전', '2026-11-01T02:00:00Z')", third);
        insertMessage(NEW_INQUIRY_ID, 4, "AI", "", "{}", "GENERATING"); // 저장이 끝나지 않은 메시지는 보이지 않는다
        insertMessage(OTHER_INQUIRY_ID, 1, "USER", "다른 문의", "{}", "COMPLETED");

        mockMvc.perform(asMember(get("/api/v2/inquiries/" + NEW_INQUIRY_ID + "/messages").param("size", "2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].sequenceNo").value(contains(2, 3)))
                .andExpect(jsonPath("$.nextBeforeSequenceNo").value(2))
                .andExpect(jsonPath("$.items[0].sender").value("AI"))
                .andExpect(jsonPath("$.items[0].requestId").hasJsonPath())
                .andExpect(jsonPath("$.items[0].requestId").isEmpty())
                .andExpect(jsonPath("$.items[0].problemIds").isEmpty())
                .andExpect(jsonPath("$.items[0].revisions").isEmpty())
                .andExpect(jsonPath("$.items[1].id").value(third.toString()))
                .andExpect(jsonPath("$.items[1].inquiryId").value(NEW_INQUIRY_ID.toString()))
                .andExpect(jsonPath("$.items[1].sender").value("USER"))
                .andExpect(jsonPath("$.items[1].content").value("고친 내용"))
                .andExpect(jsonPath("$.items[1].revision").value(3))
                // 같은 문제가 여러 구간으로 이어져도 한 번만
                .andExpect(jsonPath("$.items[1].problemIds").value(contains(PROBLEM_A.toString(), PROBLEM_B.toString())))
                .andExpect(jsonPath("$.items[1].createdAt").value("2026-11-01T00:00:00Z"))
                // 각 원문의 시각은 그 내용을 쓴 시각이다: 1차는 처음 보낸 시각, 2차는 1차를 고친 시각
                .andExpect(jsonPath("$.items[1].revisions[*].revisionNo").value(contains(1, 2)))
                .andExpect(jsonPath("$.items[1].revisions[*].content").value(contains("처음 원문", "고치기 전")))
                .andExpect(jsonPath("$.items[1].revisions[*].createdAt")
                        .value(contains("2026-11-01T00:00:00Z", "2026-11-01T01:00:00Z")));

        mockMvc.perform(asMember(get("/api/v2/inquiries/" + NEW_INQUIRY_ID + "/messages")
                        .param("size", "2").param("beforeSequenceNo", "2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id").value(contains(first.toString())))
                .andExpect(jsonPath("$.items[0].requestId").value(REQUEST_ID.toString()))
                .andExpect(jsonPath("$.nextBeforeSequenceNo").hasJsonPath())
                .andExpect(jsonPath("$.nextBeforeSequenceNo").isEmpty());
    }

    @Test
    void 메시지가_없으면_빈_페이지다() throws Exception {
        mockMvc.perform(asMember(get("/api/v2/inquiries/" + OLD_INQUIRY_ID + "/messages")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.nextBeforeSequenceNo").isEmpty());
    }

    @Test
    void 로그인하지_않으면_401이다() throws Exception {
        for (String path : new String[] {"/api/v2/inquiries", "/api/v2/inquiries/" + NEW_INQUIRY_ID,
                "/api/v2/inquiries/" + NEW_INQUIRY_ID + "/messages"}) {
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
        return insertRun(inquiryId, "SUCCEEDED");
    }

    private UUID insertRun(UUID inquiryId, String status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO agent_run (id, inquiry_id, task, run_status, input_version, input_snapshot, "
                + "model_version, prompt_version, schema_version, started_at, completed_at) "
                + "VALUES (?, ?, 'INQUIRY_ANALYSIS', ?, 1, '{}', 'gpt-test', 'prompt-v1', 'schema-v1', now(), now())",
                id, inquiryId, status);
        return id;
    }

    // 분석 중인 실행을 현재 실행으로 둔다. 입력의 problemIds가 이 실행이 대상으로 삼은 문제다
    private void queueRun(List<UUID> targetProblemIds) {
        UUID trigger = insertMessage(NEW_INQUIRY_ID, 1, "USER", "답했어요", "{\"requestId\": \"" + REQUEST_ID + "\"}",
                "COMPLETED");
        UUID queued = UUID.randomUUID();
        String targets = targetProblemIds.stream().map(id -> "\"" + id + "\"").reduce((a, b) -> a + "," + b).orElse("");
        jdbcTemplate.update("INSERT INTO agent_run (id, inquiry_id, trigger_message_id, task, run_status, input_version, "
                + "input_snapshot, model_version, prompt_version, schema_version) VALUES (?, ?, ?, 'INQUIRY_ANALYSIS', "
                + "'QUEUED', 1, ?::jsonb, 'pending', 'pending', 'hops-system-1')", queued, NEW_INQUIRY_ID, trigger,
                "{\"answerMessageId\": \"" + UUID.randomUUID() + "\", \"problemIds\": [" + targets + "]}");
        jdbcTemplate.update("UPDATE inquiry SET current_run_id = ? WHERE id = ?", queued, NEW_INQUIRY_ID);
    }

    private UUID problem(UUID inquiryId, int problemNo, String status, int clarifyCount, String creationOutcome,
            String ticketErrorCode) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO inquiry_problem (id, inquiry_id, problem_no, title, problem_status, clarify_count, "
                + "creation_outcome, creation_input_version, ticket_error_code) VALUES (?, ?, ?, '문제', ?, ?, ?, ?, ?)",
                id, inquiryId, problemNo, status, clarifyCount, creationOutcome, creationOutcome == null ? null : 1,
                ticketErrorCode);
        return id;
    }

    private UUID insertRequest(UUID problemId, UUID runId, String summary, UUID reportedSystemId, int inquiryVersion) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO standard_request (id, inquiry_problem_id, inquiry_version, agent_run_id, intent, "
                + "work_type, reported_system_id, responsible_system_id, summary, confirmed_facts) "
                + "VALUES (?, ?, ?, ?, 'OBSERVED_SYMPTOM', 'INVESTIGATION', ?, ?, ?, '[{\"text\": \"내부 사실\", "
                + "\"sourceMessageIds\": [], \"knowledgeEvidenceIds\": []}]')",
                id, problemId, inquiryVersion, runId, reportedSystemId, reportedSystemId, summary);
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
