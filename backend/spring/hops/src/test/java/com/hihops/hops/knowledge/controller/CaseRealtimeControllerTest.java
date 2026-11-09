package com.hihops.hops.knowledge.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.hihops.hops.realtime.service.RealtimeService;

/** 사례 검토 화면의 SSE(CASE) 구독은 상세 조회와 같이 현재 사례 시스템의 주담당자만 연결한다 (WIRE_CONTRACT 12.4). */
class CaseRealtimeControllerTest extends CaseDraftFixture {
    @Autowired
    private RealtimeService realtime;

    @AfterEach
    void 구독_정리() {
        realtime.disconnect(staff);
        realtime.disconnect(other);
    }

    @Test
    void 사례_시스템의_주담당자는_사례를_구독한다() throws Exception {
        UUID caseId = draft();

        mvc.perform(as(staff, subscribe(caseId))).andExpect(request().asyncStarted());
    }

    @Test
    void 다른_담당자와_없는_사례는_구분하지_않고_404다() throws Exception {
        UUID caseId = draft();

        mvc.perform(as(other, subscribe(caseId))).andExpect(status().isNotFound());
        mvc.perform(as(staff, subscribe(UUID.randomUUID()))).andExpect(status().isNotFound());
    }

    @Test
    void 주담당자가_바뀌면_이전_담당자의_새_구독을_거부한다() throws Exception {
        UUID caseId = draft();
        jdbc.update("UPDATE it_system SET primary_owner_member_id=? WHERE id=?", other, system);

        mvc.perform(as(staff, subscribe(caseId))).andExpect(status().isNotFound());
        mvc.perform(as(other, subscribe(caseId))).andExpect(request().asyncStarted());
    }

    private static MockHttpServletRequestBuilder subscribe(UUID caseId) {
        return get("/api/v2/realtime/events").param("targetType", "CASE").param("targetId", caseId.toString());
    }
}
