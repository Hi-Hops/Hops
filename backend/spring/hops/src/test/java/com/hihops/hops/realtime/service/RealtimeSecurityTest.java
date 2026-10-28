package com.hihops.hops.realtime.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.global.security.JwtConfig;
import com.hihops.hops.global.security.SecurityConfig;
import com.hihops.hops.global.security.SecurityErrorResponder;
import com.hihops.hops.realtime.controller.RealtimeController;

@WebMvcTest(RealtimeController.class)
@Import({SecurityConfig.class, SecurityErrorResponder.class, JwtConfig.class})
class RealtimeSecurityTest {
    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private RealtimeService realtime;

    @Test
    void 인증_없는_스트림은_401이다() throws Exception {
        mvc.perform(get("/api/realtime/events").param("targetType", "DEPARTMENT_TICKETS")
                        .param("targetId", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void 대상_권한_거부는_SSE_연결_전_JSON으로_반환한다() throws Exception {
        doThrow(new BusinessException(ErrorCode.ACCESS_DENIED)).when(realtime).connect(any(), any(), any());
        mvc.perform(get("/api/realtime/events").with(jwt()).param("targetType", "DEPARTMENT_TICKETS")
                        .param("targetId", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void 브라우저의_재접속_헤더를_허용한다() throws Exception {
        mvc.perform(options("/api/realtime/events").header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization,Last-Event-ID"))
                .andExpect(status().isOk());
    }
}
