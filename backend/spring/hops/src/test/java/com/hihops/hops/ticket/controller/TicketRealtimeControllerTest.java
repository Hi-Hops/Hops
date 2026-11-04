package com.hihops.hops.ticket.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import com.hihops.hops.realtime.service.RealtimeService;

/** 실제 명령 commit과 권한 회수를 거쳐 SSE의 공개 범위를 검사한다. */
class TicketRealtimeControllerTest extends TicketCommandFixture {
    @Autowired
    private RealtimeService realtime;

    @AfterEach void 구독_정리() { realtime.disconnect(staff); realtime.disconnect(other); }

    @Test void 처리_저장_후_티켓과_시스템에_신호를_보내고_권한회수후에는_닫는다() throws Exception {
        var ticketStream = mvc.perform(as(staff, get("/api/v2/realtime/events").param("targetType", "TICKET")
                .param("targetId", ticket.toString()))).andExpect(request().asyncStarted()).andReturn();
        var systemStream = mvc.perform(as(staff, get("/api/v2/realtime/events").param("targetType", "SYSTEM_TICKETS")
                .param("targetId", system.toString()))).andExpect(request().asyncStarted()).andReturn();
        var stale = (tools.jackson.databind.node.ObjectNode) work("PROGRESS"); stale.put("expectedTicketRevision", 2);
        mvc.perform(command(staff, "/tickets/" + ticket + "/work-records", stale)).andExpect(status().isConflict());
        assertThat(events(ticketStream)).isEmpty();
        result(command(staff, "/tickets/" + ticket + "/work-records", work("PROGRESS")));
        assertThat(events(ticketStream)).hasSize(1);
        assertThat(events(systemStream)).hasSize(1);
        var event = events(ticketStream).getFirst();
        assertThat(event.path("targetType").asString()).isEqualTo("TICKET");
        assertThat(event.path("resourceId").asString()).isEqualTo(ticket.toString());
        assertThat(event.path("revision").asLong()).isPositive();
        jdbc.update("UPDATE it_system SET primary_owner_member_id=? WHERE id=?", other, system);
        var next = (tools.jackson.databind.node.ObjectNode) work("PROGRESS"); next.put("expectedTicketRevision", 2);
        result(command(other, "/tickets/" + ticket + "/work-records", next));
        assertThat(events(ticketStream)).hasSize(1);
        assertThat(events(systemStream)).hasSize(1);
    }

    private List<tools.jackson.databind.JsonNode> events(MvcResult stream) throws Exception {
        return stream.getResponse().getContentAsString().lines().filter(line -> line.startsWith("data:"))
                .map(line -> mapper.readTree(line.substring(5))).toList();
    }
}
