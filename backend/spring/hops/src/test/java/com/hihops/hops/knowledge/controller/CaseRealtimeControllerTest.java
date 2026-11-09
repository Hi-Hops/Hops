package com.hihops.hops.knowledge.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.hihops.hops.realtime.service.RealtimeService;

/** 사례 검토 화면의 SSE(CASE) 구독은 상세 조회와 같이 현재 사례 시스템의 주담당자만 연결한다 (WIRE_CONTRACT 12.4). */
@TestPropertySource(properties = "hops.realtime.heartbeat=100ms")
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

    @Test
    void 열린_구독도_주담당자가_바뀌면_다음_heartbeat에서_닫힌다() throws Exception {
        UUID caseId = draft();
        MvcResult stream = mvc.perform(as(staff, subscribe(caseId))).andExpect(request().asyncStarted()).andReturn();
        Awaitility.await().atMost(Duration.ofSeconds(5))
                .until(() -> stream.getResponse().getContentAsString().contains(":heartbeat"));

        jdbc.update("UPDATE it_system SET primary_owner_member_id=? WHERE id=?", other, system);

        // 권한 재검사가 실패하면 연결을 완료(complete)한다. 완료되지 않으면 기다린 뒤 IllegalStateException이다.
        stream.getAsyncResult(5_000);
        int heartbeats = heartbeats(stream);
        Thread.sleep(300);
        assertThat(heartbeats(stream)).isEqualTo(heartbeats);
    }

    private static int heartbeats(MvcResult stream) throws Exception {
        return stream.getResponse().getContentAsString().split(":heartbeat", -1).length - 1;
    }

    private static MockHttpServletRequestBuilder subscribe(UUID caseId) {
        return get("/api/v2/realtime/events").param("targetType", "CASE").param("targetId", caseId.toString());
    }
}
