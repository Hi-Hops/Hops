package com.hihops.hops.ticket.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.hihops.hops.knowledge.service.QueryEmbedding;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.hihops.hops.realtime.service.RealtimeService;
import com.hihops.hops.ticket.repository.TicketQueryRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** F007/F009·WIRE12의 실제 PostgreSQL 조회·공개 범위 계약. 테스트 데이터는 트랜잭션 종료 시 되돌린다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"db", "test"})
@Transactional
class TicketControllerTest {
    @Test void F009_CONTRACT_08_지원자료는_현재_주담당자에게만_공개하고_빈결과와_권한거부를_구분한다() throws Exception {
        String path="/tickets/"+TICKET+"/assistance";
        mvc.perform(as(REQUESTER,path)).andExpect(status().isForbidden());
        mvc.perform(as(OTHER,path)).andExpect(status().isForbidden());
        mvc.perform(as(ADMIN,path)).andExpect(status().isForbidden());
        verifyNoInteractions(embedding);
        String vector="["+String.join(",",java.util.Collections.nCopies(1536,"0.1"))+"]";
        when(embedding.embed(org.mockito.ArgumentMatchers.anyString())).thenReturn(vector);
        mvc.perform(as(STAFF,path)).andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketId").value(TICKET.toString()))
                .andExpect(jsonPath("$.materials.length()").value(0));
    }
    @Test void 조회는_처리기록이나_AI실행을_생성하지_않는다() throws Exception {
        var records = jdbc.queryForObject("SELECT count(*) FROM ticket_work_log WHERE ticket_id=?", Integer.class, TICKET);
        var runs = jdbc.queryForObject("SELECT count(*) FROM agent_run WHERE inquiry_id=?", Integer.class, INQUIRY);
        mvc.perform(as(STAFF, "/tickets/" + TICKET)).andExpect(status().isOk());
        mvc.perform(as(STAFF, "/tickets")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_work_log WHERE ticket_id=?", Integer.class, TICKET)).isEqualTo(records);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run WHERE inquiry_id=?", Integer.class, INQUIRY)).isEqualTo(runs);
    }

    @Test void 다른_사례_저장흐름의_재사용없음_결과도_저장된_사실로_조회한다() throws Exception {
        UUID workRecord = jdbc.queryForObject("SELECT id FROM ticket_work_log WHERE ticket_id=?", UUID.class, TICKET);
        UUID generation = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agent_run(id,inquiry_id,ticket_id,trigger_work_log_id,task,run_status,input_version,input_snapshot,
                    model_version,prompt_version,schema_version,started_at,completed_at,result,validation_result)
                VALUES (?,?,?,?,'CURATION','SUCCEEDED',1,'{}','test','test','hops-system-1',now(),now(),
                    '{"caseProposal":{"reusable":false}}','{"applied":true,"resources":[]}')
                """, generation, INQUIRY, TICKET, workRecord);
        mvc.perform(as(STAFF, "/case-generations/" + generation)).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("NO_REUSABLE_CASE"))
                .andExpect(jsonPath("$.caseId").isEmpty());
        jdbc.update("UPDATE agent_run SET result='{\"caseProposal\":{\"reusable\":true}}' WHERE id=?", generation);
        entities.clear();
        mvc.perform(as(STAFF, "/case-generations/" + generation)).andExpect(status().isServiceUnavailable());
    }

    @Test void 추천은_현재_담당자만_조회하며_검색_실패는_빈_결과로_숨기지_않는다() throws Exception {
        String path = "/tickets/" + TICKET + "/recommended-cases";
        mvc.perform(as(REQUESTER, path)).andExpect(status().isForbidden());
        mvc.perform(as(OTHER, path)).andExpect(status().isForbidden());
        mvc.perform(as(ADMIN, path)).andExpect(status().isForbidden());
        verifyNoInteractions(embedding);
        when(embedding.embed("문서 오류")).thenThrow(new TransientDataAccessResourceException("test"));
        mvc.perform(as(STAFF, path)).andExpect(status().isServiceUnavailable());
    }

    @Test void 담당자는_추천_가능한_사례가_없으면_빈_목록을_받는다() throws Exception {
        String vector = "[" + String.join(",", java.util.Collections.nCopies(1536, "0.1")) + "]";
        when(embedding.embed("문서 오류")).thenReturn(vector);
        mvc.perform(as(STAFF, "/tickets/" + TICKET + "/recommended-cases"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    private static final UUID DEPARTMENT = UUID.fromString("71000000-0000-7000-8000-000000000001");
    private static final UUID STAFF = UUID.fromString("71000000-0000-7000-8000-000000000002");
    private static final UUID OTHER = UUID.fromString("71000000-0000-7000-8000-000000000003");
    private static final UUID ADMIN = UUID.fromString("71000000-0000-7000-8000-000000000004");
    private static final UUID REQUESTER = UUID.fromString("71000000-0000-7000-8000-000000000005");
    private static final UUID SYSTEM = UUID.fromString("71000000-0000-7000-8000-000000000006");
    private static final UUID OTHER_SYSTEM = UUID.fromString("71000000-0000-7000-8000-000000000007");
    private static final UUID INQUIRY = UUID.fromString("71000000-0000-7000-8000-000000000008");
    private static final UUID RUN = UUID.fromString("71000000-0000-7000-8000-000000000009");
    private static final UUID TICKET = UUID.fromString("71000000-0000-7000-8000-000000000010");
    private static final UUID SECOND = UUID.fromString("71000000-0000-7000-8000-000000000011");
    private static final UUID FOREIGN = UUID.fromString("71000000-0000-7000-8000-000000000012");
    private static final UUID PROBLEM = UUID.fromString("71000000-0000-7000-8000-000000000013");
    private static final UUID MESSAGE = UUID.fromString("71000000-0000-7000-8000-000000000014");

    @MockitoBean
    private QueryEmbedding embedding;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private jakarta.persistence.EntityManager entities;
    @MockitoSpyBean
    private TicketQueryRepository tickets;
    @Autowired
    private RealtimeService realtime;

    @AfterEach
    void 열린_테스트_스트림을_정리한다() {
        for (UUID member : List.of(STAFF, OTHER, ADMIN, REQUESTER)) realtime.disconnect(member);
    }

    @BeforeEach
    void 같은_부서의_서로_다른_시스템_담당자와_티켓을_준비한다() {
        jdbc.update("INSERT INTO department(id,code,name) VALUES (?, 'TICKET_QUERY_TEST', '조회 테스트팀')", DEPARTMENT);
        for (UUID member : List.of(STAFF, OTHER, ADMIN, REQUESTER)) {
            jdbc.update("INSERT INTO member(id,login_id,employee_no,password_hash,name,department_id) VALUES (?,?,?,?,?,?)",
                    member, "query." + member, member.toString(), "test-hash", "조회 사용자", DEPARTMENT);
        }
        jdbc.update("INSERT INTO member_role(member_id,role) VALUES (?, 'BIZ_ADMIN')", ADMIN);
        jdbc.update("INSERT INTO it_system(id,code,name,primary_owner_member_id) VALUES (?, 'ELECTRONIC_APPROVAL', '전자결재', ?)", SYSTEM, STAFF);
        jdbc.update("INSERT INTO it_system(id,code,name,primary_owner_member_id) VALUES (?, 'FILE_SHARING', '파일 공유', ?)", OTHER_SYSTEM, OTHER);
        jdbc.update("INSERT INTO system_feature(it_system_id,code,name) VALUES (?, 'VIEW', '문서 조회')", SYSTEM);
        jdbc.update("INSERT INTO inquiry(id,inquiry_number,requester_member_id,title,inquiry_status) VALUES (?, 'Q-QUERY', ?, '복합 문의', 'IN_PROGRESS')", INQUIRY, REQUESTER);
        jdbc.update("""
                INSERT INTO agent_run(id,inquiry_id,task,run_status,input_version,input_snapshot,model_version,prompt_version,schema_version,started_at,completed_at)
                VALUES (?,?,'INQUIRY_ANALYSIS','SUCCEEDED',1,'{}','test','test','hops-system-1',now(),now())
                """, RUN, INQUIRY);
        ticket(TICKET, PROBLEM, 1, SYSTEM);
        ticket(SECOND, UUID.randomUUID(), 2, SYSTEM);
        ticket(FOREIGN, UUID.randomUUID(), 3, OTHER_SYSTEM);
        jdbc.update("""
                INSERT INTO ticket_assignment(ticket_id,it_system_id,assignee_member_id,source,reason,assigned_at)
                VALUES (?,?,?,'AUTO','최초 배정',now())
                """, TICKET, SYSTEM, OTHER);
        jdbc.update("""
                INSERT INTO ticket_work_log(ticket_id,author_member_id,log_type,action_detail,verification_result)
                VALUES (?,?,'RESOLVED','실제 조치','검증 완료')
                """, TICKET, STAFF);
    }

    private void ticket(UUID ticket, UUID problem, int number, UUID system) {
        UUID standard = UUID.randomUUID();
        jdbc.update("INSERT INTO inquiry_problem(id,inquiry_id,problem_no,title,problem_status) VALUES (?,?,?,'문서 오류','TICKET_CREATED')", problem, INQUIRY, number);
        jdbc.update("""
                INSERT INTO standard_request(id,inquiry_problem_id,inquiry_version,agent_run_id,intent,work_type,reported_system_id,responsible_system_id,summary)
                VALUES (?,?,1,?,'OBSERVED_SYMPTOM','INVESTIGATION',?,?,'문서 오류 요약')
                """, standard, problem, RUN, system, system);
        // 과거 배정인은 OTHER다. 접근 권한은 SYSTEM의 현재 PRIMARY인 STAFF에게 있다.
        jdbc.update("""
                INSERT INTO ticket(id,ticket_number,inquiry_id,inquiry_problem_id,standard_request_id,title,detail,work_type,responsible_system_id,assignee_member_id,updated_at)
                VALUES (?,?,?,?,?,'문서 오류','담당자 전용 내용','INVESTIGATION',?,?,'2026-11-01T00:00:00Z')
                """, ticket, "TK-QUERY-" + number, INQUIRY, problem, standard, system, OTHER);
    }

    private MockHttpServletRequestBuilder as(UUID user, String path) {
        return get("/api/v2" + path).with(jwt().jwt(token -> token.subject(user.toString())
                .claim("departmentId", DEPARTMENT.toString())
                .claim("policyRevision", 1)
                .claim("primarySystemIds", jdbc.queryForList("SELECT id::text FROM it_system WHERE primary_owner_member_id=? AND deleted_at IS NULL", String.class, user))
                .claim("roles", user.equals(ADMIN) ? List.of("BIZ_ADMIN") : List.of())
                .expiresAt(Instant.now().plusSeconds(600))));
    }

    @Test void 배정_근거는_현재_문제와_조회자의_공개_범위로_제한한다() throws Exception {
        jdbc.update("""
                INSERT INTO message(id,inquiry_id,sequence_no,sender_type,content,message_status,payload)
                VALUES (?,?,1,'USER','허용','COMPLETED',?::jsonb)
                """, MESSAGE, INQUIRY, "{\"problemLinks\":[{\"problemId\":\"" + PROBLEM
                + "\",\"revision\":1,\"spans\":[{\"start\":0,\"end\":2}]}]}");
        jdbc.update("UPDATE ticket_assignment SET evidence=?::jsonb WHERE ticket_id=?",
                "{\"messageIds\":[\"" + MESSAGE + "\",\"" + UUID.randomUUID() + "\"],\"note\":null}", TICKET);
        mvc.perform(as(STAFF, "/tickets/" + TICKET)).andExpect(status().isOk())
                .andExpect(jsonPath("$.assignmentHistory[0].evidenceMessageIds.length()").value(1));
        mvc.perform(as(ADMIN, "/management/tickets/" + TICKET)).andExpect(status().isOk())
                .andExpect(jsonPath("$.assignmentHistory[0].evidenceMessageIds").isEmpty());
    }

    @Test void 계약에_없는_시스템_코드를_성공으로_반환하지_않는다() throws Exception {
        jdbc.update("UPDATE it_system SET code='UNSUPPORTED_DEMO' WHERE id=?", SYSTEM);
        mvc.perform(as(STAFF, "/systems")).andExpect(status().isServiceUnavailable());
        mvc.perform(as(STAFF, "/tickets")).andExpect(status().isServiceUnavailable());
    }

    @Test void 발급_당시_담당_문맥이_바뀌면_AUTH_CONTEXT_CHANGED를_반환한다() throws Exception {
        var request = as(STAFF, "/tickets/" + TICKET).with(jwt().jwt(token -> token.subject(STAFF.toString())
                .claim("departmentId", DEPARTMENT.toString()).claim("roles", List.of())
                .claim("primarySystemIds", List.of(SYSTEM.toString()))
                .claim("policyRevision", 1).expiresAt(Instant.now().plusSeconds(600))));
        jdbc.update("UPDATE it_system SET primary_owner_member_id=? WHERE id=?", OTHER, SYSTEM);
        mvc.perform(request).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_CONTEXT_CHANGED"));
    }

    @Test void 시스템_목록에는_기능만_있고_담당자_정보는_없다() throws Exception {
        mvc.perform(as(REQUESTER, "/systems")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("ELECTRONIC_APPROVAL"))
                .andExpect(jsonPath("$[0].features[0].name").value("문서 조회"))
                .andExpect(jsonPath("$[0].primaryOwner").doesNotExist())
                .andExpect(jsonPath("$[0].primaryUser").doesNotExist());
    }

    @Test void 현재_담당_시스템만_안정_정렬과_페이지로_조회한다() throws Exception {
        mvc.perform(as(STAFF, "/tickets").param("size", "1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(SECOND.toString()))
                .andExpect(jsonPath("$.items[0].state").value("WAITING"))
                .andExpect(jsonPath("$.items[0].ticketRevision").value(1))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(as(STAFF, "/tickets").param("size", "1").param("page", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(TICKET.toString()));
    }

    @Test void 필터의_빈_결과와_조회_권한_없음을_구분한다() throws Exception {
        mvc.perform(as(STAFF, "/tickets").param("state", "RESOLVED")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(as(STAFF, "/tickets").param("systemId", OTHER_SYSTEM.toString()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(as(REQUESTER, "/tickets")).andExpect(status().isForbidden());
    }

    @Test void 관리자는_ADMIN_범위의_업무_메타데이터만_조회한다() throws Exception {
        mvc.perform(as(ADMIN, "/tickets").param("scope", "ADMIN")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(as(STAFF, "/tickets").param("scope", "ADMIN")).andExpect(status().isForbidden());
        mvc.perform(as(ADMIN, "/management/tickets/" + TICKET)).andExpect(status().isOk())
                .andExpect(jsonPath("$.currentPrimary.id").value(STAFF.toString()))
                .andExpect(jsonPath("$.assignmentHistory[0].reason").value("최초 배정"))
                .andExpect(jsonPath("$.standardRequest").doesNotExist())
                .andExpect(jsonPath("$.workRecords").doesNotExist())
                .andExpect(jsonPath("$.detail").doesNotExist());
        mvc.perform(as(ADMIN, "/tickets/" + TICKET)).andExpect(status().isForbidden());
        mvc.perform(as(STAFF, "/management/tickets/" + TICKET)).andExpect(status().isForbidden());
    }

    @Test void 상세는_저장_배정과_현재_담당자_및_계약_필수_키를_구분한다() throws Exception {
        mvc.perform(as(STAFF, "/tickets/" + TICKET)).andExpect(status().isOk())
                .andExpect(jsonPath("$.assignee.id").value(OTHER.toString()))
                .andExpect(jsonPath("$.currentPrimary.id").value(STAFF.toString()))
                .andExpect(jsonPath("$.problemId").value(PROBLEM.toString()))
                .andExpect(jsonPath("$.workItemId").value(PROBLEM.toString()))
                .andExpect(jsonPath("$.standardRequest.details.acceptanceCriteria").isArray())
                .andExpect(jsonPath("$.workRecords[0].kind").value("VERIFIED_RESOLUTION"));
        JsonNode body = body(as(STAFF, "/tickets/" + TICKET));
        assertThat(body.has("activeResolutionRequest")).isTrue();
        assertThat(body.has("caseGeneration")).isTrue();
        assertThat(body.path("standardRequest").path("details").has("taskOrScreen")).isTrue();
        assertThat(body.has("summary")).isFalse();
    }

    @Test void 과거_배정인과_문의자에게_담당자_상세를_공개하지_않는다() throws Exception {
        mvc.perform(as(OTHER, "/tickets/" + TICKET)).andExpect(status().isForbidden());
        mvc.perform(as(REQUESTER, "/tickets/" + TICKET)).andExpect(status().isForbidden());
        mvc.perform(as(STAFF, "/tickets/" + UUID.randomUUID())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test void 담당자가_바뀌면_오래된_토큰으로_재조회할_수_없다() throws Exception {
        mvc.perform(as(STAFF, "/tickets/" + TICKET)).andExpect(status().isOk());
        jdbc.update("UPDATE it_system SET primary_owner_member_id=?, ownership_version=ownership_version+1 WHERE id=?", OTHER, SYSTEM);
        mvc.perform(as(STAFF, "/tickets/" + TICKET)).andExpect(status().isForbidden());
        mvc.perform(as(STAFF, "/tickets/" + TICKET + "/related-messages")).andExpect(status().isForbidden());
        mvc.perform(as(OTHER, "/tickets/" + TICKET)).andExpect(status().isOk());
    }

    @Test void 잘못된_필터_범위와_빈_입력을_임의로_보정하지_않는다() throws Exception {
        for (String[] input : List.of(new String[]{"page", "-1"}, new String[]{"size", "0"},
                new String[]{"size", "101"}, new String[]{"state", "OPEN"}, new String[]{"scope", "ALL"},
                new String[]{"page", ""}, new String[]{"systemId", "invalid"})) {
            mvc.perform(as(STAFF, "/tickets").param(input[0], input[1])).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value(input[0]));
        }
        mvc.perform(get("/api/v2/tickets")).andExpect(status().isUnauthorized());
    }

    @Test void 삭제된_티켓은_목록과_상세에서_제외한다() throws Exception {
        jdbc.update("UPDATE ticket SET deleted_at=now() WHERE id=?", TICKET);
        mvc.perform(as(STAFF, "/tickets")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(as(STAFF, "/tickets/" + TICKET)).andExpect(status().isNotFound());
    }

    @Test void 보관한_완료티켓은_목록에서만_제외하고_출처_상세와_권한은_유지한다() throws Exception {
        jdbc.update("UPDATE ticket SET ticket_status='RESOLVED', resolved_at=now(), archived_at=now() WHERE id=?", TICKET);
        entities.clear();
        mvc.perform(as(STAFF, "/tickets")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(SECOND.toString()));
        mvc.perform(as(STAFF, "/tickets").param("state", "RESOLVED")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(as(ADMIN, "/tickets").param("scope", "ADMIN")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(as(STAFF, "/tickets/" + TICKET)).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RESOLVED"));
        mvc.perform(as(REQUESTER, "/tickets/" + TICKET)).andExpect(status().isForbidden());
    }

    @Test void 복합_원문과_정정_이력을_문제별_Unicode_구간으로_투영한다() throws Exception {
        messages();
        JsonNode first = body(as(STAFF, "/tickets/" + TICKET + "/related-messages").param("size", "1"));
        assertThat(first.path("items").get(0).path("content").asString()).isEqualTo("결재😀오류");
        assertThat(first.path("items").get(0).path("revisions").get(0).path("content").asString()).isEqualTo("결재이전");
        assertThat(first.toString()).doesNotContain("파일공유비밀", "다른 문제 비밀", "sequenceNo");
        String cursor = first.path("nextCursor").asString();
        JsonNode second = body(as(STAFF, "/tickets/" + TICKET + "/related-messages").param("size", "1").param("cursor", cursor));
        assertThat(second.path("items").get(0).path("content").asString()).isEqualTo("추가보완");
        assertThat(second.path("nextCursor").isNull()).isTrue();
    }

    @Test void 안전한_연결이_없는_메시지는_제외하고_잘못된_커서는_거부한다() throws Exception {
        messages();
        jdbc.update("INSERT INTO message(inquiry_id,sequence_no,sender_type,content,payload,message_status) VALUES (?,99,'USER','공개하면 안 되는 원문','{}','COMPLETED')", INQUIRY);
        mvc.perform(as(STAFF, "/tickets/" + TICKET + "/related-messages")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));
        mvc.perform(as(STAFF, "/tickets/" + TICKET + "/related-messages").param("cursor", "invalid"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(ADMIN, "/tickets/" + TICKET + "/related-messages")).andExpect(status().isForbidden());
    }

    @Test void 복수_시스템을_담당하면_각_시스템의_업무를_모두_조회한다() throws Exception {
        jdbc.update("UPDATE it_system SET primary_owner_member_id=? WHERE id=?", STAFF, OTHER_SYSTEM);
        mvc.perform(as(STAFF, "/tickets")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(as(STAFF, "/tickets").param("systemId", OTHER_SYSTEM.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test void 조회_중_토큰_만료와_관리자_역할_회수를_현재_정보로_검사한다() throws Exception {
        mvc.perform(get("/api/v2/tickets").with(jwt().jwt(token -> token.subject(STAFF.toString())
                .claim("departmentId", DEPARTMENT.toString()).claim("roles", List.of())
                .expiresAt(Instant.now().minusSeconds(1)))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("ACCESS_TOKEN_EXPIRED"));
        jdbc.update("UPDATE member_role SET deleted_at=now() WHERE member_id=?", ADMIN);
        mvc.perform(as(ADMIN, "/tickets").param("scope", "ADMIN"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH_CONTEXT_CHANGED"));
    }

    @Test void 유효하지_않은_저장_JSON을_빈_정상_결과로_바꾸지_않는다() throws Exception {
        jdbc.update("UPDATE standard_request SET type_detail='{\"acceptanceCriteria\":\"invalid\"}' WHERE inquiry_problem_id=?", PROBLEM);
        mvc.perform(as(STAFF, "/tickets/" + TICKET)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
    }

    @Test void 저장된_근거도_현재_검수와_공개_범위를_다시_검사한다() throws Exception {
        UUID document = UUID.randomUUID();
        String evidence = """
                [{"id":"%s","documentId":"%s","documentVersion":1,"kind":"GUIDE","title":"직원 가이드",
                "excerpt":"가이드 본문","systemId":"%s","appliesToSystemVersion":null,"applicability":"문서 조회",
                "accessScope":"SYSTEM_STAFF","origin":"SYNTHETIC"}]
                """.formatted(UUID.randomUUID(), document, SYSTEM);
        jdbc.update("""
                INSERT INTO knowledge_document(id,title,document_type,it_system_id,access_level,data_split,review_status)
                VALUES (?,'직원 가이드','EMPLOYEE_GUIDE',?,'STAFF','RAG','APPROVED')
                """, document, SYSTEM);
        String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest("가이드 본문".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        jdbc.update("""
                INSERT INTO knowledge_document_version(knowledge_document_id,version_no,content,content_digest,source_metadata,review_status)
                VALUES (?,1,'가이드 본문',?,'{"origin":"SYNTHETIC","applicability":"문서 조회","appliesToSystemVersion":null}','APPROVED')
                """, document, digest);
        jdbc.update("UPDATE standard_request SET related_knowledge=?::jsonb WHERE inquiry_problem_id=?", evidence, PROBLEM);
        mvc.perform(as(STAFF, "/tickets/" + TICKET)).andExpect(status().isOk())
                .andExpect(jsonPath("$.standardRequest.knowledge[0].excerpt").value("가이드 본문"));
        jdbc.update("UPDATE knowledge_document SET review_status='PENDING' WHERE id=?", document);
        mvc.perform(as(STAFF, "/tickets/" + TICKET)).andExpect(status().isOk())
                .andExpect(jsonPath("$.standardRequest.knowledge").isEmpty());
    }

    @Test void 상태와_버전은_DB_코드를_직접_노출하지_않는다() throws Exception {
        jdbc.update("UPDATE ticket SET ticket_status='WAITING_CONFIRM',version=4 WHERE id=?", TICKET);
        mvc.perform(as(STAFF, "/tickets").param("state", "WAITING_CONFIRMATION"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].state").value("WAITING_CONFIRMATION"))
                .andExpect(jsonPath("$.items[0].ticketRevision").value(5));
    }

    @Test void 일시적_DB_조회_장애는_503이며_빈_정상_결과가_아니다() throws Exception {
        doThrow(new TransientDataAccessResourceException("테스트 DB 장애")).when(tickets).findActiveDetail(TICKET);
        mvc.perform(as(STAFF, "/tickets/" + TICKET)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test void 과거_원문에_안전한_구간이_없으면_그_정정_이력은_공개하지_않는다() throws Exception {
        messages();
        jdbc.update("UPDATE message SET payload=?::jsonb WHERE id=?",
                """
                {"problemLinks":[{"problemId":"%s","revision":2,"spans":[{"start":0,"end":5}]}]}
                """.formatted(PROBLEM), MESSAGE);
        mvc.perform(as(STAFF, "/tickets/" + TICKET + "/related-messages")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].content").value("결재😀오류"))
                .andExpect(jsonPath("$.items[0].revisions").isEmpty());
    }

    @Test void 관련_대화_커서는_다른_티켓에_재사용할_수_없다() throws Exception {
        messages();
        String cursor = body(as(STAFF, "/tickets/" + TICKET + "/related-messages").param("size", "1"))
                .path("nextCursor").asString();
        mvc.perform(as(STAFF, "/tickets/" + SECOND + "/related-messages").param("cursor", cursor))
                .andExpect(status().isBadRequest());
    }

    @Test void 다른_문제의_메시지만_출처로_삼은_확인_사실은_노출하지_않는다() throws Exception {
        UUID otherMessage = UUID.randomUUID();
        UUID otherProblem = jdbc.queryForObject("SELECT inquiry_problem_id FROM ticket WHERE id=?", UUID.class, FOREIGN);
        jdbc.update("INSERT INTO message(id,inquiry_id,sequence_no,sender_type,content,payload,message_status) VALUES (?,?,9,'USER','다른 문제의 비밀',?::jsonb,'COMPLETED')",
                otherMessage, INQUIRY, """
                {"problemLinks":[{"problemId":"%s","revision":1,"spans":[{"start":0,"end":9}]}]}
                """.formatted(otherProblem));
        jdbc.update("UPDATE standard_request SET confirmed_facts=?::jsonb WHERE inquiry_problem_id=?",
                """
                [{"text":"다른 문제의 비밀","sourceMessageIds":["%s"],"knowledgeEvidenceIds":[]}]
                """.formatted(otherMessage), PROBLEM);
        mvc.perform(as(STAFF, "/tickets/" + TICKET)).andExpect(status().isOk())
                .andExpect(jsonPath("$.standardRequest.confirmedFacts").isEmpty());
    }

    @Test void 저장한_해결_확인_요청과_실행_중_사례_생성을_별도_상태로_조회한다() throws Exception {
        UUID workRecord = jdbc.queryForObject("SELECT id FROM ticket_work_log WHERE ticket_id=?", UUID.class, TICKET);
        UUID resolution = UUID.randomUUID();
        UUID curation = UUID.randomUUID();
        String request = """
                {"resolutionRequest":{"id":"%s","inquiryId":"%s","problemId":"%s","ticketId":"%s",
                "source":"WORK_RECORD","sourceId":"%s","sourceVersion":1,"requestRevision":1,"state":"PENDING",
                "resultSummary":"조치 후 확인 요청","confirmationOrigin":"HOPS_USER","createdAt":"2026-11-02T00:00:00Z","respondedAt":null}}
                """.formatted(resolution, INQUIRY, PROBLEM, TICKET, workRecord);
        jdbc.update("INSERT INTO ticket_event(ticket_id,event_type,actor_type,after_data) VALUES (?,'STATUS_CHANGED','SYSTEM',?::jsonb)", TICKET, request);
        jdbc.update("UPDATE ticket SET ticket_status='WAITING_CONFIRM',confirm_requested_at=now() WHERE id=?", TICKET);
        jdbc.update("""
                INSERT INTO agent_run(id,inquiry_id,ticket_id,trigger_work_log_id,task,run_status,input_version,input_snapshot,model_version,prompt_version,schema_version,started_at)
                VALUES (?,?,?,?,'CURATION','RUNNING',1,'{}','test','test','hops-system-1',now())
                """, curation, INQUIRY, TICKET, workRecord);
        mvc.perform(as(STAFF, "/tickets/" + TICKET)).andExpect(status().isOk())
                .andExpect(jsonPath("$.activeResolutionRequest.id").value(resolution.toString()))
                .andExpect(jsonPath("$.activeResolutionRequest.state").value("PENDING"))
                .andExpect(jsonPath("$.caseGeneration.id").value(curation.toString()))
                .andExpect(jsonPath("$.caseGeneration.state").value("RUNNING"))
                .andExpect(jsonPath("$.caseGeneration.caseId").isEmpty());
    }

    @Test void 확인_요청_메타데이터가_없는데_시각만으로_요청을_만들지_않는다() throws Exception {
        jdbc.update("UPDATE ticket SET ticket_status='WAITING_CONFIRM',confirm_requested_at=now() WHERE id=?", TICKET);
        mvc.perform(as(STAFF, "/tickets/" + TICKET)).andExpect(status().isServiceUnavailable());
    }

    @Test void 주담당자_미리보기는_현재_담당자와_관리자에게만_허용한다() throws Exception {
        mvc.perform(as(STAFF, "/systems/" + SYSTEM + "/primary")).andExpect(status().isOk())
                .andExpect(jsonPath("$.systemId").value(SYSTEM.toString()))
                .andExpect(jsonPath("$.primaryUser.id").value(STAFF.toString()))
                .andExpect(jsonPath("$.primaryUser.employeeNo").doesNotExist());
        mvc.perform(as(ADMIN, "/systems/" + SYSTEM + "/primary")).andExpect(status().isOk());
        mvc.perform(as(OTHER, "/systems/" + SYSTEM + "/primary")).andExpect(status().isForbidden());
        mvc.perform(as(STAFF, "/systems/" + UUID.randomUUID() + "/primary")).andExpect(status().isNotFound());
    }

    @Test void 사례_생성_조회는_원_티켓의_현재_담당자에게_저장된_상태만_반환한다() throws Exception {
        UUID workRecord = jdbc.queryForObject("SELECT id FROM ticket_work_log WHERE ticket_id=?", UUID.class, TICKET);
        UUID generation = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agent_run(id,inquiry_id,ticket_id,trigger_work_log_id,task,run_status,input_version,input_snapshot,model_version,prompt_version,schema_version)
                VALUES (?,?,?,?,'CURATION','QUEUED',1,'{}','test','test','hops-system-1')
                """, generation, INQUIRY, TICKET, workRecord);
        mvc.perform(as(STAFF, "/case-generations/" + generation)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(generation.toString()))
                .andExpect(jsonPath("$.state").value("PENDING"));
        mvc.perform(as(ADMIN, "/case-generations/" + generation)).andExpect(status().isForbidden());
        mvc.perform(as(OTHER, "/case-generations/" + generation)).andExpect(status().isForbidden());
        mvc.perform(as(STAFF, "/case-generations/" + RUN)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run WHERE task='CURATION'", Long.class)).isEqualTo(1L);
    }

    @Test void 담당자_시스템과_티켓_SSE를_허용하고_관리자는_시스템_메타데이터만_구독한다() throws Exception {
        mvc.perform(as(STAFF, "/realtime/events").param("targetType", "SYSTEM_TICKETS").param("targetId", SYSTEM.toString()))
                .andExpect(status().isOk()).andExpect(request().asyncStarted());
        mvc.perform(as(STAFF, "/realtime/events").param("targetType", "TICKET").param("targetId", TICKET.toString()))
                .andExpect(status().isOk()).andExpect(request().asyncStarted());
        mvc.perform(as(ADMIN, "/realtime/events").param("targetType", "SYSTEM_TICKETS").param("targetId", SYSTEM.toString()))
                .andExpect(status().isOk()).andExpect(request().asyncStarted());
        mvc.perform(as(ADMIN, "/realtime/events").param("targetType", "TICKET").param("targetId", TICKET.toString()))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE it_system SET primary_owner_member_id=? WHERE id=?", OTHER, SYSTEM);
        mvc.perform(as(STAFF, "/realtime/events").param("targetType", "SYSTEM_TICKETS").param("targetId", SYSTEM.toString()))
                .andExpect(status().isForbidden());
    }

    @Test void 관련_대화_revision은_문자열이나_소수에서_정수로_보정하지_않는다() throws Exception {
        messages();
        jdbc.update("UPDATE message SET payload=?::jsonb WHERE id=?",
                """
                {"problemLinks":[{"problemId":"%s","revision":"2","spans":[{"start":0,"end":5}]}]}
                """.formatted(PROBLEM), MESSAGE);
        mvc.perform(as(STAFF, "/tickets/" + TICKET + "/related-messages")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].content").value("추가보완"));
    }

    @Test void 필수_보고_시스템이_없는_티켓을_빈_목록으로_숨기지_않는다() throws Exception {
        jdbc.update("UPDATE standard_request SET reported_system_id=null WHERE inquiry_problem_id=?", PROBLEM);
        mvc.perform(as(STAFF, "/tickets")).andExpect(status().isServiceUnavailable());
    }

    @Test void 상세의_빈_문자열은_미확인_null과_구분한다() throws Exception {
        jdbc.update("UPDATE standard_request SET type_detail='{\"taskOrScreen\":\"\"}' WHERE inquiry_problem_id=?", PROBLEM);
        mvc.perform(as(STAFF, "/tickets/" + TICKET)).andExpect(status().isServiceUnavailable());
    }

    /**
     * 1차 SQL 검색의 공개 규칙(hops.knowledge.case-visibility=APPROVED_SNAPSHOT, 기본): 승인된 현재 사례 버전이면 색인 상태와 무관하게 공개한다.
     * 계약 원안(INDEXED, 양쪽 SUCCEEDED)은 KnowledgeSearchApiTest가 같은 reader로 확인한다.
     * 사례 문서 버전은 실제 승인 거래(CaseApprovalWriter)와 같은 형식이다: digest는 승인 스냅샷의 정규 JSON SHA-256이다.
     */
    @Test void 사례_근거는_승인된_현재_버전이면_색인_상태와_무관하게_공개한다() throws Exception {
        UUID workRecord = jdbc.queryForObject("SELECT id FROM ticket_work_log WHERE ticket_id=?", UUID.class, TICKET);
        UUID caseId = UUID.randomUUID();
        UUID document = UUID.randomUUID();
        jdbc.update("UPDATE ticket SET ticket_status='RESOLVED',resolved_at=now() WHERE id=?", TICKET);
        jdbc.update("""
                INSERT INTO resolved_case(id,case_number,source_work_log_id,it_system_id,case_type,title,problem_statement,
                action_detail,verification_result,applicability,access_level,origin_type,case_status,approved_by_member_id,approved_at,vector_status,graph_status)
                VALUES (?,'CASE-QUERY',?,?,'RESOLUTION','승인 사례','문서 오류','조치','검증','문서 조회','STAFF','SYNTHETIC','APPROVED',?,now(),'SUCCEEDED','PENDING')
                """, caseId, workRecord, SYSTEM, STAFF);
        jdbc.update("INSERT INTO knowledge_document(id,title,document_type,it_system_id,access_level,data_split,review_status) VALUES (?,'승인 사례','CASE',?,'STAFF','RAG','APPROVED')", document, SYSTEM);
        String approvedCase = "{\"action\":\"조치\",\"applicability\":\"문서 조회\",\"problemStatement\":\"문서 오류\",\"title\":\"승인 사례\",\"verification\":\"검증\"}";
        String caseContent = "승인 사례\n\n문서 오류\n\n조치\n\n검증\n\n문서 조회";
        String snapshot = "{\"approvedCase\":" + approvedCase + ",\"caseId\":\"" + caseId + "\",\"caseVersion\":1}";
        String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        jdbc.update("""
                INSERT INTO knowledge_document_version(knowledge_document_id,version_no,content,content_digest,source_metadata,review_status)
                VALUES (?,1,?,?,?::jsonb,'APPROVED')
                """, document, caseContent, digest, """
                {"caseId":"%s","caseVersion":1,"indexRequests":{"VECTOR":{"approvedCase":%s}}}
                """.formatted(caseId, approvedCase));
        jdbc.update("UPDATE standard_request SET related_knowledge=?::jsonb WHERE inquiry_problem_id=?", """
                [{"id":"%s","documentId":"%s","documentVersion":1,"kind":"CASE","title":"승인 사례","excerpt":%s,
                "systemId":"%s","appliesToSystemVersion":null,"applicability":"문서 조회","accessScope":"SYSTEM_STAFF","origin":"SYNTHETIC"}]
                """.formatted(UUID.randomUUID(), caseId, new tools.jackson.databind.json.JsonMapper().writeValueAsString(caseContent), SYSTEM), PROBLEM);
        // 한쪽만 색인된 상태에서도 승인된 현재 버전이면 공개한다. 색인 상태는 그대로 PENDING이다.
        mvc.perform(as(STAFF, "/tickets/" + TICKET)).andExpect(status().isOk()).andExpect(jsonPath("$.standardRequest.knowledge.length()").value(1));
        jdbc.update("UPDATE resolved_case SET vector_status='PENDING', graph_status='PENDING' WHERE id=?", caseId);
        mvc.perform(as(STAFF, "/tickets/" + TICKET)).andExpect(status().isOk()).andExpect(jsonPath("$.standardRequest.knowledge.length()").value(1));
        // 사례 버전이 바뀌면 저장된 근거의 버전과 달라져 공개하지 않는다.
        jdbc.update("UPDATE resolved_case SET version_no=2 WHERE id=?", caseId);
        mvc.perform(as(STAFF, "/tickets/" + TICKET)).andExpect(status().isOk()).andExpect(jsonPath("$.standardRequest.knowledge").isEmpty());
    }

    @Test void 티켓이_다른_문제의_표준_요청을_가리키면_공개하지_않는다() throws Exception {
        jdbc.update("UPDATE ticket SET standard_request_id=(SELECT standard_request_id FROM ticket WHERE id=?) WHERE id=?", FOREIGN, TICKET);
        mvc.perform(as(STAFF, "/tickets/" + TICKET)).andExpect(status().isServiceUnavailable());
        mvc.perform(as(STAFF, "/tickets")).andExpect(status().isServiceUnavailable());
    }

    @Test void 사례_생성의_원_처리_기록이_다른_티켓이면_거부한다() throws Exception {
        UUID workRecord = UUID.randomUUID();
        UUID generation = UUID.randomUUID();
        jdbc.update("INSERT INTO ticket_work_log(id,ticket_id,author_member_id,log_type,action_detail,verification_result) VALUES (?,?,?,'RESOLVED','조치','검증')", workRecord, SECOND, STAFF);
        jdbc.update("""
                INSERT INTO agent_run(id,inquiry_id,ticket_id,trigger_work_log_id,task,run_status,input_version,input_snapshot,model_version,prompt_version,schema_version)
                VALUES (?,?,?,?,'CURATION','QUEUED',1,'{}','test','test','hops-system-1')
                """, generation, INQUIRY, TICKET, workRecord);
        mvc.perform(as(STAFF, "/case-generations/" + generation)).andExpect(status().isServiceUnavailable());
    }

    @Test void 허용된_관련_메시지에_근거한_확인_사실은_보존한다() throws Exception {
        messages();
        jdbc.update("UPDATE standard_request SET confirmed_facts=?::jsonb WHERE inquiry_problem_id=?", """
                [{"text":"결재 오류를 확인함","sourceMessageIds":["%s"],"knowledgeEvidenceIds":[]}]
                """.formatted(MESSAGE), PROBLEM);
        mvc.perform(as(STAFF, "/tickets/" + TICKET)).andExpect(status().isOk())
                .andExpect(jsonPath("$.standardRequest.confirmedFacts[0].text").value("결재 오류를 확인함"));
    }

    private void messages() {
        String links = """
                {"problemLinks":[{"problemId":"%s","revision":2,"spans":[{"start":0,"end":5}]},
                {"problemId":"%s","revision":1,"spans":[{"start":0,"end":4}]}]}
                """.formatted(PROBLEM, PROBLEM);
        jdbc.update("INSERT INTO message(id,inquiry_id,sequence_no,sender_type,content,revision,payload,message_status) VALUES (?,?,7,'USER','결재😀오류 / 파일공유비밀',2,?::jsonb,'COMPLETED')", MESSAGE, INQUIRY, links);
        jdbc.update("INSERT INTO message_revision(message_id,revision_no,content) VALUES (?,1,'결재이전 / 다른 문제 비밀')", MESSAGE);
        jdbc.update("INSERT INTO message(inquiry_id,sequence_no,sender_type,content,payload,message_status) VALUES (?,42,'USER','추가보완',?::jsonb,'COMPLETED')", INQUIRY,
                """
                {"problemLinks":[{"problemId":"%s","revision":1,"spans":[{"start":0,"end":4}]}]}
                """.formatted(PROBLEM));
    }

    private JsonNode body(MockHttpServletRequestBuilder request) throws Exception {
        return mapper.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
}
