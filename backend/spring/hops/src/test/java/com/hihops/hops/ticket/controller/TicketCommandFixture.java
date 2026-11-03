package com.hihops.hops.ticket.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 명령의 실제 commit·재전송·현재 버전 충돌을 검사하므로 테스트 자체를 트랜잭션으로 감싸지 않는다. */
@SpringBootTest(properties = "hops.agent.dispatch-enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles({"db", "test"})
abstract class TicketCommandFixture {
    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected ObjectMapper mapper;
    protected final UUID department = UUID.randomUUID();
    protected final UUID staff = UUID.randomUUID();
    protected final UUID other = UUID.randomUUID();
    protected final UUID admin = UUID.randomUUID();
    protected final UUID requester = UUID.randomUUID();
    protected final UUID system = UUID.randomUUID();
    protected final UUID otherSystem = UUID.randomUUID();
    protected final UUID inquiry = UUID.randomUUID();
    protected final UUID problem = UUID.randomUUID();
    protected final UUID analysis = UUID.randomUUID();
    protected final UUID standard = UUID.randomUUID();
    protected final UUID ticket = UUID.randomUUID();
    protected final UUID message = UUID.randomUUID();

    @BeforeEach void 준비() {
        jdbc.update("INSERT INTO department(id,code,name) VALUES (?,?,'명령 테스트팀')", department, "cmd-" + department.toString().substring(0, 12));
        for (var user : List.of(staff, other, admin, requester)) {
            jdbc.update("INSERT INTO member(id,login_id,employee_no,password_hash,name,department_id) VALUES (?,?,?,?,?,?)",
                    user, user.toString(), user.toString(), "synthetic-hash", "가상 사용자", department);
        }
        jdbc.update("INSERT INTO member_role(member_id,role) VALUES (?,'BIZ_ADMIN')", admin);
        jdbc.update("INSERT INTO it_system(id,code,name,primary_owner_member_id) VALUES (?,'ELECTRONIC_APPROVAL','결재',?)", system, staff);
        jdbc.update("INSERT INTO it_system(id,code,name,primary_owner_member_id) VALUES (?,'FILE_SHARING','파일',?)", otherSystem, other);
        jdbc.update("INSERT INTO inquiry(id,inquiry_number,requester_member_id,title,inquiry_status) VALUES (?,?,?,'가상 문의','IN_PROGRESS')",
                inquiry, "Q-" + inquiry.toString().substring(0, 12), requester);
        jdbc.update("INSERT INTO agent_run(id,inquiry_id,task,run_status,input_version,input_snapshot,model_version,prompt_version,schema_version,started_at,completed_at) VALUES (?,?,'INQUIRY_ANALYSIS','SUCCEEDED',1,'{}','test','test','hops-system-1',now(),now())", analysis, inquiry);
        jdbc.update("INSERT INTO inquiry_problem(id,inquiry_id,problem_no,title,problem_status) VALUES (?,?,1,'문제','TICKET_CREATED')", problem, inquiry);
        jdbc.update("INSERT INTO standard_request(id,inquiry_problem_id,inquiry_version,agent_run_id,intent,work_type,reported_system_id,responsible_system_id,summary) VALUES (?,?,1,?,'OBSERVED_SYMPTOM','INVESTIGATION',?,?,'가상 요청')", standard, problem, analysis, system, system);
        jdbc.update("INSERT INTO ticket(id,ticket_number,inquiry_id,inquiry_problem_id,standard_request_id,title,detail,work_type,responsible_system_id,assignee_member_id) VALUES (?,?,?,?,?,'문제','가상 내용','INVESTIGATION',?,?)",
                ticket, "TK-" + ticket.toString().substring(0, 12), inquiry, problem, standard, system, staff);
        jdbc.update("INSERT INTO ticket_assignment(ticket_id,it_system_id,assignee_member_id,source,reason,assigned_at) VALUES (?,?,?,'AUTO','최초',now())", ticket, system, staff);
        jdbc.update("INSERT INTO message(id,inquiry_id,sequence_no,sender_type,content,message_status,payload) VALUES (?,?,1,'AI','안내','COMPLETED',?::jsonb)",
                message, inquiry, "{\"problemLinks\":[{\"problemId\":\"" + problem + "\",\"revision\":1,\"spans\":[{\"start\":0,\"end\":2}]}]}");
    }

    @AfterEach void 정리() {
        // 테스트가 만든 문의와 회원만 제거하며 다른 테스트·개발 DB는 건드리지 않는다.
        jdbc.update("UPDATE inquiry SET current_run_id=NULL WHERE id=?", inquiry);
        jdbc.update("UPDATE inquiry_problem SET agent_run_id=NULL WHERE inquiry_id=?", inquiry);
        jdbc.update("DELETE FROM notification WHERE inquiry_id=? OR ticket_id=?", inquiry, ticket);
        jdbc.update("DELETE FROM resolved_case WHERE source_work_log_id IN (SELECT id FROM ticket_work_log WHERE ticket_id=?)", ticket);
        jdbc.update("DELETE FROM agent_run WHERE inquiry_id=? AND task='CURATION'", inquiry);
        jdbc.update("DELETE FROM ticket_event WHERE ticket_id=?", ticket);
        jdbc.update("DELETE FROM ticket_assignment WHERE ticket_id=?", ticket);
        jdbc.update("DELETE FROM ticket_work_log WHERE ticket_id=?", ticket);
        jdbc.update("DELETE FROM ticket WHERE id=?", ticket);
        jdbc.update("DELETE FROM standard_request WHERE inquiry_problem_id IN (SELECT id FROM inquiry_problem WHERE inquiry_id=?)", inquiry);
        jdbc.update("DELETE FROM inquiry_problem WHERE inquiry_id=?", inquiry);
        jdbc.update("DELETE FROM agent_run WHERE inquiry_id=?", inquiry);
        jdbc.update("DELETE FROM message WHERE inquiry_id=?", inquiry);
        jdbc.update("DELETE FROM inquiry WHERE id=?", inquiry);
        jdbc.update("DELETE FROM system_feature WHERE it_system_id IN (?,?)", system, otherSystem);
        jdbc.update("DELETE FROM it_system WHERE id IN (?,?)", system, otherSystem);
        for (var user : List.of(staff, other, admin, requester)) {
            jdbc.update("DELETE FROM idempotency_key WHERE actor_member_id=?", user);
            jdbc.update("DELETE FROM member_role WHERE member_id=?", user);
            jdbc.update("DELETE FROM member WHERE id=?", user);
        }
        jdbc.update("DELETE FROM department WHERE id=?", department);
    }

    protected MockHttpServletRequestBuilder as(UUID user, MockHttpServletRequestBuilder request) {
        return request.with(jwt().jwt(token -> token.subject(user.toString()).claim("departmentId", department.toString())
                .claim("roles", user.equals(admin) ? List.of("BIZ_ADMIN") : List.of()).claim("policyRevision", 1)
                .claim("primarySystemIds", jdbc.queryForList("SELECT id::text FROM it_system WHERE primary_owner_member_id=? AND deleted_at IS NULL", String.class, user))
                .expiresAt(Instant.now().plusSeconds(600))));
    }

    protected JsonNode json(String value) { return mapper.readTree(value); }
    protected JsonNode work(String kind) {
        return json("{\"requestId\":\"" + UUID.randomUUID() + "\",\"expectedTicketRevision\":1,\"kind\":\"" + kind
                + "\",\"action\":\"실제 조치\",\"verification\":\"정상 동작 확인\",\"confirmedCause\":null,\"appliesToSystemVersion\":null}");
    }
    protected MockHttpServletRequestBuilder command(UUID user, String path, JsonNode body) {
        return as(user, post("/api/v2" + path).contentType("application/json")
                .header("Idempotency-Key", body.path("requestId").asString()).content(body.toString()));
    }
    protected JsonNode result(MockHttpServletRequestBuilder request) throws Exception {
        var response = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse();
        return json(response.getContentAsString());
    }
    protected JsonNode detail() throws Exception { return result(as(staff, get("/api/v2/tickets/" + ticket))); }

    protected JsonNode correction(boolean assignment) {
        return json("{\"requestId\":\"" + UUID.randomUUID() + "\",\"expectedTicketRevision\":1,"
                + (assignment ? "\"responsibleSystemId\":\"" + otherSystem + "\"," : "\"processingType\":\"INCIDENT\",")
                + "\"reason\":\"담당자가 확인한 정정 사유\",\"evidenceMessageIds\":[],\"evidenceNote\":\"직접 관측한 근거\"}");
    }

}
