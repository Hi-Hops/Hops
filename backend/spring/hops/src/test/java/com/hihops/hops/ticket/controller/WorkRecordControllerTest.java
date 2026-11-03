package com.hihops.hops.ticket.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;

class WorkRecordControllerTest extends TicketCommandFixture {
    @Test void 확인대기_중에는_진행과_실패기록을_거절하고_미해결_응답후_허용한다() throws Exception {
        var path = "/tickets/" + ticket + "/work-records";
        result(command(staff, path, work("VERIFIED_RESOLUTION")));
        var waiting = detail();
        for (var kind : List.of("PROGRESS", "FAILED_ATTEMPT")) {
            var body = (tools.jackson.databind.node.ObjectNode) work(kind);
            body.put("expectedTicketRevision", waiting.path("ticketRevision").asInt());
            mvc.perform(command(staff, path, body)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        }
        var unchanged = detail();
        assertThat(unchanged.path("state")).isEqualTo(waiting.path("state"));
        assertThat(unchanged.path("ticketRevision")).isEqualTo(waiting.path("ticketRevision"));
        assertThat(unchanged.path("workRecords")).isEqualTo(waiting.path("workRecords"));
        assertThat(unchanged.path("activeResolutionRequest")).isEqualTo(waiting.path("activeResolutionRequest"));
        var response = mapper.createObjectNode().put("requestId", java.util.UUID.randomUUID().toString())
                .put("expectedRequestRevision", 1).put("decision", "UNRESOLVED");
        result(command(requester, "/resolution-requests/" + waiting.path("activeResolutionRequest").path("id").asString() + "/responses", response));
        for (var kind : List.of("PROGRESS", "FAILED_ATTEMPT")) {
            var body = (tools.jackson.databind.node.ObjectNode) work(kind);
            body.put("expectedTicketRevision", detail().path("ticketRevision").asInt());
            result(command(staff, path, body));
        }
        assertThat(detail().path("state").asString()).isEqualTo("IN_PROGRESS");
        assertThat(detail().path("workRecords").size()).isEqualTo(3);
    }

    @Test void 진행_기록은_한번만_저장하고_같은_키는_버전_비교_전에_재확인한다() throws Exception {
        var body = work("PROGRESS");
        var first = result(command(staff, "/tickets/" + ticket + "/work-records", body));
        var second = result(command(staff, "/tickets/" + ticket + "/work-records", body));
        assertThat(second).isEqualTo(first);
        assertThat(first.path("resource").path("type").asString()).isEqualTo("TICKET");
        assertThat(detail().path("state").asString()).isEqualTo("IN_PROGRESS");
        assertThat(detail().path("ticketRevision").asInt()).isEqualTo(2);
        assertThat(detail().path("workRecords").size()).isEqualTo(1);
        assertThat(detail().path("activeResolutionRequest").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run WHERE ticket_id=?", Integer.class, ticket)).isZero();
    }

    @Test void 검증_완료는_기록_확인요청_사례생성_접수를_같이_저장한다() throws Exception {
        result(command(staff, "/tickets/" + ticket + "/work-records", work("VERIFIED_RESOLUTION")));
        var value = detail();
        assertThat(value.path("state").asString()).isEqualTo("WAITING_CONFIRMATION");
        assertThat(value.path("activeResolutionRequest").path("state").asString()).isEqualTo("PENDING");
        assertThat(value.path("caseGeneration").path("state").asString()).isIn("PENDING", "FAILED");
    }

    @Test void 권한_버전_입력_오류는_업무를_저장하지_않는다() throws Exception {
        var path = "/tickets/" + ticket + "/work-records";
        mvc.perform(command(other, path, work("PROGRESS"))).andExpect(status().isForbidden());
        var stale = (tools.jackson.databind.node.ObjectNode) work("PROGRESS"); stale.put("expectedTicketRevision", 2);
        mvc.perform(command(staff, path, stale)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TICKET_REVISION_CONFLICT"));
        var invalid = (tools.jackson.databind.node.ObjectNode) work("VERIFIED_RESOLUTION"); invalid.putNull("verification");
        mvc.perform(command(staff, path, invalid)).andExpect(status().isBadRequest());
        invalid = (tools.jackson.databind.node.ObjectNode) work("PROGRESS"); invalid.remove("confirmedCause");
        mvc.perform(command(staff, path, invalid)).andExpect(status().isBadRequest());
        assertThat(detail().path("workRecords").isEmpty()).isTrue();
    }

    @Test void 기능요청과_확인대기와_최종완료의_완료기록_규칙을_지킨다() throws Exception {
        jdbc.update("UPDATE ticket SET work_type='CHANGE_REQUEST' WHERE id=?", ticket);
        mvc.perform(command(staff, "/tickets/" + ticket + "/work-records", work("VERIFIED_RESOLUTION")))
                .andExpect(status().isBadRequest());
        result(command(staff, "/tickets/" + ticket + "/work-records", work("IMPLEMENTED_VERIFIED")));
        var next = (tools.jackson.databind.node.ObjectNode) work("IMPLEMENTED_VERIFIED"); next.put("expectedTicketRevision", 2);
        mvc.perform(command(staff, "/tickets/" + ticket + "/work-records", next)).andExpect(status().isConflict());
        jdbc.update("UPDATE ticket SET ticket_status='RESOLVED',resolved_at=now() WHERE id=?", ticket);
        mvc.perform(command(staff, "/tickets/" + ticket + "/work-records", next)).andExpect(status().isConflict());
    }

    @Test void 같은키의_다른본문과_헤더불일치는_저장하지_않는다() throws Exception {
        var path = "/tickets/" + ticket + "/work-records";
        var body = work("PROGRESS"); result(command(staff, path, body));
        var changed = (tools.jackson.databind.node.ObjectNode) body.deepCopy(); changed.put("action", "다른 조치");
        mvc.perform(command(staff, path, changed)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        mvc.perform(as(staff, post("/api/v2" + path).contentType("application/json").content(work("PROGRESS").toString())))
                .andExpect(status().isBadRequest());
        assertThat(detail().path("workRecords").size()).isEqualTo(1);
    }

    @Test void 다른키로_동시에_같은버전을_변경하면_한건만_저장한다() throws Exception {
        var path = "/tickets/" + ticket + "/work-records";
        var one = command(staff, path, work("PROGRESS"));
        var two = command(staff, path, work("PROGRESS"));
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var results = pool.invokeAll(List.<java.util.concurrent.Callable<Integer>>of(
                    () -> mvc.perform(one).andReturn().getResponse().getStatus(),
                    () -> mvc.perform(two).andReturn().getResponse().getStatus()));
            assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(detail().path("workRecords").size()).isEqualTo(1);
    }
}
