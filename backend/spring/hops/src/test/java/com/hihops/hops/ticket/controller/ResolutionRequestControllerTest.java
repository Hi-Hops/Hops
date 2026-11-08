package com.hihops.hops.ticket.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ResolutionRequestControllerTest extends TicketCommandFixture {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"WORK_RECORD", "GUIDE"})
    void 권한밖_확인요청과_없는_UUID는_조회와_응답에서_같은_404를_반환한다(String source) throws Exception {
        String id;
        if ("WORK_RECORD".equals(source)) {
            result(command(staff, "/tickets/" + ticket + "/work-records", work("VERIFIED_RESOLUTION")));
            id = detail().path("activeResolutionRequest").path("id").asString();
        } else {
            var guided = UUID.randomUUID(); var resolution = UUID.randomUUID();
            jdbc.update("INSERT INTO inquiry_problem(id,inquiry_id,problem_no,title,problem_status,confirm_requested_at) VALUES (?,?,2,'안내 문제','GUIDED',now())", guided, inquiry);
            var request = new com.hihops.hops.ticket.dto.ResolutionRequestResponse(resolution, inquiry, guided, null,
                    "GUIDE", message, 1, 1, "PENDING", "안내", "HOPS_USER", Instant.now(), null);
            jdbc.update("UPDATE message SET payload=?::jsonb WHERE id=?", mapper.writeValueAsString(java.util.Map.of("resolutionRequest", request,
                    "guide", java.util.Map.of("problemId", guided, "text", "안내", "evidenceIds", List.of()))), message);
            id = resolution.toString();
        }
        for (var hidden : List.of(id, UUID.randomUUID().toString())) {
            String path = "/resolution-requests/" + hidden;
            for (var unauthorized : List.of(other, admin)) {
                mvc.perform(as(unauthorized, get("/api/v2" + path))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
                mvc.perform(command(unauthorized, path + "/responses", mapper.createObjectNode()
                        .put("requestId", UUID.randomUUID().toString()).put("expectedRequestRevision", 1).put("decision", "RESOLVED")))
                        .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
            }
        }
        mvc.perform(as(requester, get("/api/v2/resolution-requests/" + id))).andExpect(status().isOk());
        if ("WORK_RECORD".equals(source)) mvc.perform(as(staff, get("/api/v2/resolution-requests/" + id))).andExpect(status().isOk());
        mvc.perform(command(requester, "/resolution-requests/" + id + "/responses", mapper.createObjectNode()
                .put("requestId", UUID.randomUUID().toString()).put("expectedRequestRevision", 1).put("decision", "RESOLVED"))).andExpect(status().isOk());
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.hihops.hops.inquiry.service.InquiryAnswerHandler answers;
    @org.springframework.beans.factory.annotation.Autowired
    private com.hihops.hops.agent.repository.AgentRunRepository runs;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.transaction.support.TransactionTemplate transaction;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void 마지막_티켓_확인_뒤_분석이_끝나면_현재_문제와_미적용_새문제를_기준으로_문의_상태를_집계한다(
            boolean newProblem, boolean explicitFalse) throws Exception {
        result(command(staff, "/tickets/" + ticket + "/work-records", work("VERIFIED_RESOLUTION")));
        String resolutionId = detail().path("activeResolutionRequest").path("id").asString();
        jdbc.update("UPDATE agent_run SET run_status='RUNNING',completed_at=NULL WHERE id=?", analysis);
        jdbc.update("UPDATE inquiry SET current_run_id=? WHERE id=?", analysis, inquiry);
        result(command(requester, "/resolution-requests/" + resolutionId + "/responses",
                mapper.createObjectNode().put("requestId", UUID.randomUUID().toString()).put("expectedRequestRevision", 1).put("decision", "RESOLVED")));
        assertThat(jdbc.queryForObject("SELECT inquiry_status FROM inquiry WHERE id=?", String.class, inquiry)).isEqualTo("IN_PROGRESS");
        var input = mapper.createObjectNode().put("answerMessageId", UUID.randomUUID().toString());
        input.putArray("messages");
        input.putObject("accessContext").put("actorId", requester.toString()).put("policyRevision", 1);
        var output = mapper.createObjectNode().put("answer", "저장된 처리 결과를 확인했습니다.");
        if (explicitFalse) output.put("requiresFollowUp", false);
        output.putArray("problemProposals");
        if (newProblem) ((tools.jackson.databind.node.ArrayNode) output.path("problemProposals")).addObject().put("problemRef", "new:remaining");
        transaction.executeWithoutResult(status -> {
            var run = runs.findById(analysis).orElseThrow();
            answers.apply(run, input, output);
            run.finish(com.hihops.hops.agent.domain.RunStatus.SUCCEEDED, Instant.now());
        });
        assertThat(jdbc.queryForObject("SELECT inquiry_status FROM inquiry WHERE id=?", String.class, inquiry))
                .isEqualTo(newProblem ? "IN_PROGRESS" : "RESOLVED");
    }

    @Test
    void 마지막_티켓이_해결되어도_추가_설명을_기다리면_새_메시지를_받는다() throws Exception {
        result(command(staff, "/tickets/" + ticket + "/work-records", work("VERIFIED_RESOLUTION")));
        String resolutionId = detail().path("activeResolutionRequest").path("id").asString();
        jdbc.update("UPDATE agent_run SET run_status='RUNNING',completed_at=NULL WHERE id=?", analysis);
        jdbc.update("UPDATE inquiry SET current_run_id=? WHERE id=?", analysis, inquiry);
        result(command(requester, "/resolution-requests/" + resolutionId + "/responses",
                mapper.createObjectNode().put("requestId", UUID.randomUUID().toString()).put("expectedRequestRevision", 1).put("decision", "RESOLVED")));
        var input = mapper.createObjectNode().put("answerMessageId", UUID.randomUUID().toString());
        input.putArray("messages");
        input.putObject("accessContext").put("actorId", requester.toString()).put("policyRevision", 1);
        var output = mapper.createObjectNode().put("answer", "문의 내용을 조금 더 알려주시겠어요?")
                .put("requiresFollowUp", true);
        output.putArray("problemProposals");
        output.putArray("questions");
        output.putArray("ticketProposals");
        transaction.executeWithoutResult(status -> {
            var run = runs.findById(analysis).orElseThrow();
            answers.apply(run, input, output);
            run.finish(com.hihops.hops.agent.domain.RunStatus.SUCCEEDED, Instant.now());
        });
        assertThat(jdbc.queryForObject("SELECT inquiry_status FROM inquiry WHERE id=?", String.class, inquiry))
                .isEqualTo("IN_PROGRESS");
        var next = mapper.createObjectNode().put("requestId", UUID.randomUUID().toString())
                .put("expectedInquiryVersion", 1).put("content", "로그인이 안 됩니다");
        next.putArray("problemIds");
        result(command(requester, "/inquiries/" + inquiry + "/messages", next));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void 추가_설명을_요청한_후_마지막_티켓이_해결되어도_문의를_유지한다(boolean newerInputWithoutRun) throws Exception {
        result(command(staff, "/tickets/" + ticket + "/work-records", work("VERIFIED_RESOLUTION")));
        String resolutionId = detail().path("activeResolutionRequest").path("id").asString();
        jdbc.update("UPDATE agent_run SET run_status='RUNNING',completed_at=NULL WHERE id=?", analysis);
        jdbc.update("UPDATE inquiry SET current_run_id=? WHERE id=?", analysis, inquiry);
        var input = mapper.createObjectNode().put("answerMessageId", UUID.randomUUID().toString());
        input.putArray("messages");
        input.putObject("accessContext").put("actorId", requester.toString()).put("policyRevision", 1);
        var output = mapper.createObjectNode().put("answer", "문의 내용을 조금 더 알려주시겠어요?")
                .put("requiresFollowUp", true);
        output.putArray("problemProposals");
        output.putArray("questions");
        output.putArray("ticketProposals");
        transaction.executeWithoutResult(status -> {
            var run = runs.findById(analysis).orElseThrow();
            String validation = answers.apply(run, input, output);
            run.storeOutcome(output.toString(), validation, null);
            run.finish(com.hihops.hops.agent.domain.RunStatus.SUCCEEDED, Instant.now());
        });
        if (newerInputWithoutRun) jdbc.update("UPDATE inquiry SET version=2 WHERE id=?", inquiry);
        result(command(requester, "/resolution-requests/" + resolutionId + "/responses",
                mapper.createObjectNode().put("requestId", UUID.randomUUID().toString())
                        .put("expectedRequestRevision", 1).put("decision", "RESOLVED")));
        assertThat(jdbc.queryForObject("SELECT inquiry_status FROM inquiry WHERE id=?", String.class, inquiry))
                .isEqualTo("IN_PROGRESS");
        var next = mapper.createObjectNode().put("requestId", UUID.randomUUID().toString())
                .put("expectedInquiryVersion", newerInputWithoutRun ? 2 : 1).put("content", "로그인이 안 됩니다");
        next.putArray("problemIds");
        result(command(requester, "/inquiries/" + inquiry + "/messages", next));
    }

    @Test
    void 추가_설명_요청_뒤_사용자가_답하면_새_실행이_없어도_마지막_티켓_해결로_문의를_완료한다() throws Exception {
        result(command(staff, "/tickets/" + ticket + "/work-records", work("VERIFIED_RESOLUTION")));
        String resolutionId = detail().path("activeResolutionRequest").path("id").asString();
        jdbc.update("UPDATE agent_run SET run_status='RUNNING',completed_at=NULL WHERE id=?", analysis);
        jdbc.update("UPDATE inquiry SET current_run_id=? WHERE id=?", analysis, inquiry);
        var input = mapper.createObjectNode().put("answerMessageId", UUID.randomUUID().toString());
        input.putArray("messages");
        input.putObject("accessContext").put("actorId", requester.toString()).put("policyRevision", 1);
        var output = mapper.createObjectNode().put("answer", "문의 내용을 조금 더 알려주시겠어요?")
                .put("requiresFollowUp", true);
        output.putArray("problemProposals");
        output.putArray("questions");
        output.putArray("ticketProposals");
        transaction.executeWithoutResult(status -> {
            var run = runs.findById(analysis).orElseThrow();
            String validation = answers.apply(run, input, output);
            run.storeOutcome(output.toString(), validation, null);
            run.finish(com.hihops.hops.agent.domain.RunStatus.SUCCEEDED, Instant.now());
        });
        // 결과를 적용한 실행의 입력에도 같은 답변 ID가 있다(실제 실행은 접수 때 고정한다).
        jdbc.update("UPDATE agent_run SET input_snapshot=?::jsonb WHERE id=?", input.toString(), analysis);
        // 배정된 문제의 보완처럼 새 실행을 시작하지 않는 사용자 입력이 답변 뒤에 저장됐다.
        jdbc.update("INSERT INTO message (id, inquiry_id, sequence_no, sender_type, content, payload, message_status) "
                + "VALUES (?, ?, (SELECT MAX(sequence_no) + 1 FROM message WHERE inquiry_id = ?), 'USER', ?, '{}'::jsonb, 'COMPLETED')",
                UUID.randomUUID(), inquiry, inquiry, "오후 2시부터 그랬어요");
        result(command(requester, "/resolution-requests/" + resolutionId + "/responses",
                mapper.createObjectNode().put("requestId", UUID.randomUUID().toString())
                        .put("expectedRequestRevision", 1).put("decision", "RESOLVED")));
        assertThat(jdbc.queryForObject("SELECT inquiry_status FROM inquiry WHERE id=?", String.class, inquiry))
                .isEqualTo("RESOLVED");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"RESOLVED", "UNRESOLVED"})
    void 문의자_문제투영은_처리기록의_확인요청과_응답후_티켓상태를_반영한다(String decision) throws Exception {
        result(command(staff, "/tickets/" + ticket + "/work-records", work("VERIFIED_RESOLUTION")));
        var request = detail().path("activeResolutionRequest");
        var inquiryPath = "/api/v2/inquiries/" + inquiry;
        var pending = result(as(requester, get(inquiryPath))).path("problems").get(0);
        assertThat(pending.path("id").asString()).isEqualTo(problem.toString());
        assertThat(pending.path("state").asString()).isEqualTo("WAITING_CONFIRMATION");
        assertThat(pending.path("activeResolutionRequest")).isEqualTo(request);
        assertThat(pending.path("ticket").path("state").asString()).isEqualTo("WAITING_CONFIRMATION");

        var body = mapper.createObjectNode().put("requestId", UUID.randomUUID().toString())
                .put("expectedRequestRevision", 1).put("decision", decision);
        result(command(requester, "/resolution-requests/" + request.path("id").asString() + "/responses", body));
        var answered = result(as(requester, get(inquiryPath))).path("problems").get(0);
        assertThat(answered.path("activeResolutionRequest")).isEqualTo(detail().path("activeResolutionRequest"));
        assertThat(answered.path("activeResolutionRequest").path("state").asString()).isEqualTo(decision);
        assertThat(answered.path("activeResolutionRequest").path("requestRevision").asInt()).isEqualTo(2);
        assertThat(answered.path("state").asString()).isEqualTo("RESOLVED".equals(decision) ? "RESOLVED" : "IN_SERVICE");
        assertThat(answered.path("ticket").path("state").asString())
                .isEqualTo("RESOLVED".equals(decision) ? "RESOLVED" : "IN_PROGRESS");
    }

    @Test void 다른_분석이_진행중이면_마지막_티켓_확인만으로_문의를_완료하지_않는다() throws Exception {
        result(command(staff, "/tickets/" + ticket + "/work-records", work("VERIFIED_RESOLUTION")));
        var id = detail().path("activeResolutionRequest").path("id").asString();
        jdbc.update("UPDATE agent_run SET run_status='RUNNING',completed_at=NULL WHERE id=?", analysis);
        jdbc.update("UPDATE inquiry SET current_run_id=? WHERE id=?", analysis, inquiry);
        var body = mapper.createObjectNode().put("requestId", UUID.randomUUID().toString())
                .put("expectedRequestRevision", 1).put("decision", "RESOLVED");
        result(command(requester, "/resolution-requests/" + id + "/responses", body));
        assertThat(jdbc.queryForObject("SELECT inquiry_status FROM inquiry WHERE id=?", String.class, inquiry)).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT version FROM inquiry WHERE id=?", Integer.class, inquiry)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT result_revision FROM inquiry WHERE id=?", Integer.class, inquiry)).isEqualTo(2);
    }
    @Test void 동시_해결응답은_한건만_확정하고_나머지는_확인요청_버전충돌이다() throws Exception {
        result(command(staff, "/tickets/" + ticket + "/work-records", work("VERIFIED_RESOLUTION")));
        var id = detail().path("activeResolutionRequest").path("id").asString();
        var body = json("{\"requestId\":\"" + UUID.randomUUID() + "\",\"expectedRequestRevision\":1,\"decision\":\"RESOLVED\"}");
        var otherBody = (tools.jackson.databind.node.ObjectNode) body.deepCopy(); otherBody.put("requestId", UUID.randomUUID().toString());
        var one = command(requester, "/resolution-requests/" + id + "/responses", body);
        var two = command(requester, "/resolution-requests/" + id + "/responses", otherBody);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var results = pool.invokeAll(List.<java.util.concurrent.Callable<org.springframework.mock.web.MockHttpServletResponse>>of(
                    () -> mvc.perform(one).andReturn().getResponse(), () -> mvc.perform(two).andReturn().getResponse()));
            var responses = List.of(results.get(0).get(), results.get(1).get());
            assertThat(responses.stream().map(value -> value.getStatus()).toList()).containsExactlyInAnyOrder(200, 409);
            for (var response : responses) if (response.getStatus() == 409) {
                assertThat(json(response.getContentAsString()).path("code").asString()).isEqualTo("RESOLUTION_REVISION_CONFLICT");
            }
        }
    }
    @Test void WORK_RECORD_확인요청은_문의자와_현재담당자가_독립적으로_조회한다() throws Exception {
        result(command(staff, "/tickets/" + ticket + "/work-records", work("VERIFIED_RESOLUTION")));
        var request = detail().path("activeResolutionRequest");
        var path = "/api/v2/resolution-requests/" + request.path("id").asString();
        assertThat(result(as(requester, get(path)))).isEqualTo(request);
        assertThat(result(as(staff, get(path)))).isEqualTo(request);
        mvc.perform(as(other, get(path))).andExpect(status().isNotFound());
        mvc.perform(as(requester, get("/api/v2/resolution-requests/" + UUID.randomUUID()))).andExpect(status().isNotFound());
    }
    @Test void 해결응답은_현재티켓을_최종완료하고_새키의_중복응답을_거절한다() throws Exception {
        result(command(staff, "/tickets/" + ticket + "/work-records", work("VERIFIED_RESOLUTION")));
        var id = detail().path("activeResolutionRequest").path("id").asString();
        var body = json("{\"requestId\":\"" + UUID.randomUUID() + "\",\"expectedRequestRevision\":1,\"decision\":\"RESOLVED\"}");
        result(command(requester, "/resolution-requests/" + id + "/responses", body));
        assertThat(detail().path("state").asString()).isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("SELECT inquiry_status FROM inquiry WHERE id=?", String.class, inquiry)).isEqualTo("RESOLVED");
        mvc.perform(as(admin, get("/api/v2/resolution-requests/" + id))).andExpect(status().isNotFound());
        var next = (tools.jackson.databind.node.ObjectNode) body.deepCopy(); next.put("requestId", UUID.randomUUID().toString());
        mvc.perform(command(requester, "/resolution-requests/" + id + "/responses", next)).andExpect(status().isConflict());
    }

    @Test void GUIDE_해결은_해당문제만_완료하고_Curator를_만들지_않는다() throws Exception {
        var guided = UUID.randomUUID(); var resolution = UUID.randomUUID();
        jdbc.update("INSERT INTO inquiry_problem(id,inquiry_id,problem_no,title,problem_status,clarify_count,confirm_requested_at) VALUES (?,?,2,'안내 문제','GUIDED',2,now())", guided, inquiry);
        var request = new com.hihops.hops.ticket.dto.ResolutionRequestResponse(resolution, inquiry, guided, null,
                "GUIDE", message, 1, 1, "PENDING", "안내 확인", "HOPS_USER", Instant.now(), null);
        jdbc.update("UPDATE message SET payload=?::jsonb WHERE id=?", mapper.writeValueAsString(java.util.Map.of("resolutionRequest", request,
                "guide", java.util.Map.of("problemId", guided, "text", "안내", "evidenceIds", List.of()))), message);
        var body = json("{\"requestId\":\"" + UUID.randomUUID() + "\",\"expectedRequestRevision\":1,\"decision\":\"RESOLVED\"}");
        result(command(requester, "/resolution-requests/" + resolution + "/responses", body));
        assertThat(jdbc.queryForObject("SELECT problem_status FROM inquiry_problem WHERE id=?", String.class, guided)).isEqualTo("RESOLVED_WITHOUT_TICKET");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run WHERE inquiry_id=?", Integer.class, inquiry)).isEqualTo(1);
        assertThat(detail().path("state").asString()).isEqualTo("WAITING");
        assertThat(jdbc.queryForObject("SELECT inquiry_status FROM inquiry WHERE id=?", String.class, inquiry)).isEqualTo("IN_PROGRESS");
    }
    @Test void 문의자만_한번_응답하며_미해결이면_같은티켓을_다시_진행한다() throws Exception {
        result(command(staff, "/tickets/" + ticket + "/work-records", work("VERIFIED_RESOLUTION")));
        var id = detail().path("activeResolutionRequest").path("id").asString();
        var body = json("{\"requestId\":\"" + UUID.randomUUID() + "\",\"expectedRequestRevision\":1,\"decision\":\"UNRESOLVED\"}");
        var path = "/resolution-requests/" + id + "/responses";
        mvc.perform(command(staff, path, body)).andExpect(status().isNotFound());
        var first = result(command(requester, path, body));
        assertThat(result(command(requester, path, body))).isEqualTo(first);
        assertThat(detail().path("state").asString()).isEqualTo("IN_PROGRESS");
        assertThat(detail().path("activeResolutionRequest").path("requestRevision").asInt()).isEqualTo(2);
        var duplicate = (tools.jackson.databind.node.ObjectNode) body.deepCopy(); duplicate.put("requestId", UUID.randomUUID().toString());
        mvc.perform(command(requester, path, duplicate)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOLUTION_REVISION_CONFLICT"));
        for (var kind : List.of("PROGRESS", "FAILED_ATTEMPT")) {
            var next = (tools.jackson.databind.node.ObjectNode) work(kind);
            next.put("expectedTicketRevision", detail().path("ticketRevision").asInt());
            result(command(staff, "/tickets/" + ticket + "/work-records", next));
        }
        assertThat(detail().path("workRecords").size()).isEqualTo(3);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void GUIDE_미해결은_해당문제의_기존_분류와_버전과_질문횟수로_재분석한다(boolean hasStandardRequest) throws Exception {
        var guided = UUID.randomUUID(); var resolution = UUID.randomUUID();
        jdbc.update("INSERT INTO inquiry_problem(id,inquiry_id,problem_no,title,problem_status,clarify_count,confirm_requested_at) VALUES (?,?,2,'안내 문제','GUIDED',2,now())", guided, inquiry);
        if (hasStandardRequest) {
            jdbc.update("INSERT INTO standard_request(id,inquiry_problem_id,inquiry_version,agent_run_id,intent,work_type,reported_system_id,responsible_system_id,summary) VALUES (?,?,1,?,'HOW_TO','KNOWLEDGE_QA',?,?,'기존 안내 요청')", UUID.randomUUID(), guided, analysis, system, system);
        }
        var request = new com.hihops.hops.ticket.dto.ResolutionRequestResponse(resolution, inquiry, guided, null,
                "GUIDE", message, 1, 1, "PENDING", "안내 후 확인", "HOPS_USER", Instant.now(), null);
        jdbc.update("UPDATE message SET payload=?::jsonb WHERE id=?", mapper.writeValueAsString(java.util.Map.of("resolutionRequest", request,
                "guide", java.util.Map.of("problemId", guided, "text", "안내", "evidenceIds", List.of()))), message);
        result(as(requester, get("/api/v2/resolution-requests/" + resolution)));
        mvc.perform(as(staff, get("/api/v2/resolution-requests/" + resolution))).andExpect(status().isNotFound());
        var body = json("{\"requestId\":\"" + UUID.randomUUID() + "\",\"expectedRequestRevision\":1,\"decision\":\"UNRESOLVED\"}");
        result(command(requester, "/resolution-requests/" + resolution + "/responses", body));
        assertThat(jdbc.queryForObject("SELECT clarify_count FROM inquiry_problem WHERE id=?", Integer.class, guided)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT version FROM inquiry WHERE id=?", Integer.class, inquiry)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT problem_status FROM inquiry_problem WHERE id=?", String.class, problem)).isEqualTo("TICKET_CREATED");
        var snapshot = jdbc.queryForObject("SELECT input_snapshot::text FROM agent_run WHERE id=(SELECT current_run_id FROM inquiry WHERE id=?)", String.class, inquiry);
        assertThat(json(snapshot).path("problemIds").get(0).asString()).isEqualTo(guided.toString());
        assertThat(json(snapshot).path("answerMessageId").asString()).isNotBlank();
        assertThat(json(snapshot).path("problems").get(0).path("intent").asString()).isEqualTo(hasStandardRequest ? "HOW_TO" : "UNKNOWN");
        if (hasStandardRequest) {
            assertThat(json(snapshot).path("problems").get(0).path("reportedSystemId").asString()).isEqualTo(system.toString());
            assertThat(json(snapshot).path("problems").get(0).path("standardRequest").path("summary").asString()).isEqualTo("기존 안내 요청");
        }
    }
}
