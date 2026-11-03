package com.hihops.hops.ticket.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

class TicketCorrectionControllerTest extends TicketCommandFixture {
    @Test void 정정사유의_길이는_UTF16이_아닌_코드포인트로_검증한다() throws Exception {
        var body = (tools.jackson.databind.node.ObjectNode) correction(false);
        body.put("reason", "😀".repeat(1001));
        mvc.perform(command(staff, "/tickets/" + ticket + "/processing-type-corrections", body)).andExpect(status().isBadRequest());
        body.put("reason", "😀".repeat(1000));
        result(command(staff, "/tickets/" + ticket + "/processing-type-corrections", body));
    }

    @Test void 같은시스템의_잘못된_배정도_현재_PRIMARY로_정정한다() throws Exception {
        jdbc.update("UPDATE ticket SET assignee_member_id=? WHERE id=?", other, ticket);
        var body = (tools.jackson.databind.node.ObjectNode) correction(true);
        body.put("responsibleSystemId", system.toString());
        result(command(staff, "/tickets/" + ticket + "/assignment-corrections", body));
        assertThat(detail().path("assignee").path("id").asString()).isEqualTo(staff.toString());
    }
    @Test void 조사_장애_정정은_현재담당자만_버전과_이력을_함께_저장한다() throws Exception {
        var body = correction(false); var path = "/tickets/" + ticket + "/processing-type-corrections";
        mvc.perform(command(admin, path, body)).andExpect(status().isForbidden());
        result(command(staff, path, body)); result(command(staff, path, body));
        assertThat(detail().path("processingType").asString()).isEqualTo("INCIDENT");
        assertThat(detail().path("state").asString()).isEqualTo("WAITING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_event WHERE ticket_id=? AND event_type='WORK_TYPE_CHANGED'", Integer.class, ticket)).isEqualTo(1);
    }

    @Test void 관리자_배정정정은_새시스템의_현재_PRIMARY를_서버가_결정한다() throws Exception {
        var body = correction(true); var path = "/tickets/" + ticket + "/assignment-corrections";
        var first = result(command(admin, path, body));
        assertThat(result(command(admin, path, body))).isEqualTo(first);
        var value = result(as(other, get("/api/v2/tickets/" + ticket)));
        assertThat(value.path("assignee").path("id").asString()).isEqualTo(other.toString());
        assertThat(value.path("assignmentHistory").size()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_assignment WHERE ticket_id=? AND ended_at IS NULL", Integer.class, ticket)).isEqualTo(1);
        mvc.perform(as(staff, get("/api/v2/tickets/" + ticket))).andExpect(status().isForbidden());
    }

    @Test void 관리자에게_허용되지_않는_메시지를_정정_근거로_받지_않는다() throws Exception {
        var body = (tools.jackson.databind.node.ObjectNode) correction(true);
        body.putArray("evidenceMessageIds").add(message.toString());
        mvc.perform(command(admin, "/tickets/" + ticket + "/assignment-corrections", body)).andExpect(status().isForbidden());
    }

    @Test void 배정정정은_진행중인_확인요청을_보존한다() throws Exception {
        result(command(staff, "/tickets/" + ticket + "/work-records", work("VERIFIED_RESOLUTION")));
        var before = detail().path("activeResolutionRequest");
        var body = (tools.jackson.databind.node.ObjectNode) correction(true); body.put("expectedTicketRevision", 2);
        result(command(admin, "/tickets/" + ticket + "/assignment-corrections", body));
        var value = result(as(other, get("/api/v2/tickets/" + ticket)));
        assertThat(value.path("activeResolutionRequest")).isEqualTo(before);
        assertThat(value.path("state").asString()).isEqualTo("WAITING_CONFIRMATION");
    }
}
