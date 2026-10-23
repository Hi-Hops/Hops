package com.hihops.hops.global.exception;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * 공통 오류 응답(docs/API.md §1)이 지켜지는지 확인한다.
 * 아래 TestErrorController는 테스트에서만 쓰는 가짜 API로, 일부러 각종 예외를 던진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@WithMockUser // 이 테스트는 오류·로그 형식을 보므로 로그인한 상태로 실행한다 (인증 규칙은 SecurityConfigTest).
@Import(GlobalExceptionHandlerTest.TestErrorController.class)
class GlobalExceptionHandlerTest {

    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 업무_예외는_오류_코드의_상태와_공통_형식으로_응답한다() throws Exception {
        mockMvc.perform(get("/test/errors/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.requestId").value(matchesPattern(UUID_PATTERN)))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void 오류_응답의_requestId는_응답_헤더와_같다() throws Exception {
        String requestId = mockMvc.perform(get("/test/errors/conflict"))
                .andExpect(header().string("X-Request-Id", matchesPattern(UUID_PATTERN)))
                .andReturn().getResponse().getHeader("X-Request-Id");

        mockMvc.perform(get("/test/errors/conflict").header("X-Request-Id", requestId))
                .andExpect(jsonPath("$.requestId").value(requestId));
    }

    @Test
    void 입력값_검증에_실패하면_400으로_응답한다() throws Exception {
        mockMvc.perform(post("/test/errors/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void JSON_형식이_잘못되면_400으로_응답한다() throws Exception {
        mockMvc.perform(post("/test/errors/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 없는_경로는_404로_응답한다() throws Exception {
        mockMvc.perform(get("/test/errors/no-such-path"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void 예상하지_못한_예외는_500으로_응답하고_내부_내용을_숨긴다() throws Exception {
        mockMvc.perform(get("/test/errors/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value(not(containsString("secret-detail"))));
    }

    @Test
    void 일시_장애는_재시도_가능으로_표시한다() throws Exception {
        mockMvc.perform(get("/test/errors/unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @TestConfiguration
    @RestController
    static class TestErrorController {

        record NameRequest(@NotBlank String name) {
        }

        @GetMapping("/test/errors/conflict")
        void conflict() {
            throw new BusinessException(ErrorCode.CONFLICT);
        }

        @GetMapping("/test/errors/unavailable")
        void unavailable() {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }

        @GetMapping("/test/errors/unexpected")
        void unexpected() {
            throw new IllegalStateException("secret-detail");
        }

        @PostMapping("/test/errors/validation")
        void validation(@Valid @RequestBody NameRequest request) {
        }
    }
}
