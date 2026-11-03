package com.hihops.hops.global.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 공통 오류 응답(docs/API.md §1)이 지켜지는지 확인한다.
 * 아래 TestErrorController는 테스트에서만 쓰는 가짜 API로, 일부러 각종 예외를 던진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"db", "test"})
@WithMockUser // 이 테스트는 오류·로그 형식을 보므로 로그인한 상태로 실행한다 (인증 규칙은 SecurityConfigTest).
@Import(GlobalExceptionHandlerTest.TestErrorController.class)
@ExtendWith(OutputCaptureExtension.class) // CapturedOutput: 테스트 중 찍힌 로그를 문자열로 받아 검사한다
class GlobalExceptionHandlerTest {

    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 업무_예외는_오류_코드의_상태와_공통_형식으로_응답한다() throws Exception {
        mockMvc.perform(get("/api/v2/test/errors/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.requestId").value(matchesPattern(UUID_PATTERN)))
                .andExpect(jsonPath("$.retryable").value(false))
                // DATA_TYPES 신규 절 ApiError.fieldErrors: 필수 배열. 입력 항목 오류가 아니면 빈 배열이다.
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
    }

    @Test
    void 오류_응답의_requestId는_응답_헤더와_같다() throws Exception {
        String requestId = mockMvc.perform(get("/api/v2/test/errors/conflict"))
                .andExpect(header().string("X-Request-Id", matchesPattern(UUID_PATTERN)))
                .andReturn().getResponse().getHeader("X-Request-Id");

        mockMvc.perform(get("/api/v2/test/errors/conflict").header("X-Request-Id", requestId))
                .andExpect(jsonPath("$.requestId").value(requestId));
    }

    @Test
    void 입력값_검증에_실패하면_400으로_응답한다() throws Exception {
        mockMvc.perform(post("/api/v2/test/errors/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                // 어떤 항목이 왜 틀렸는지 알려 프론트가 그 입력 칸에 표시한다. 입력한 값은 담지 않는다.
                .andExpect(jsonPath("$.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("NotBlank"));
    }

    @Test
    void 쿼리_값의_형식이_틀리면_그_항목을_fieldErrors에_담는다() throws Exception {
        mockMvc.perform(get("/api/v2/test/errors/count").param("count", "many"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("count"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("TypeMismatch"));
    }

    @Test
    void 필수_쿼리_값이_없으면_그_항목을_fieldErrors에_담는다() throws Exception {
        mockMvc.perform(get("/api/v2/test/errors/count"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("count"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("Required"));
    }

    @Test
    void 쿼리_값이_검증_규칙에_어긋나면_그_항목을_fieldErrors에_담는다() throws Exception {
        // 컨트롤러 파라미터에 직접 붙인 규칙(@Min 등)은 Spring이 다른 예외(HandlerMethodValidationException)로 알린다.
        mockMvc.perform(get("/api/v2/test/errors/count").param("count", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("count"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("Min"));
    }

    @Test
    void 규칙_이름이_없는_입력_오류도_빈_code로_보내지_않는다() {
        // 프론트는 code가 비어 있으면 응답 형식 오류로 본다. 직접 만든 검증 오류는 규칙 이름이 null일 수 있다.
        assertThat(GlobalExceptionHandler.fieldError("name", null))
                .isEqualTo(new ErrorResponse.FieldErrorResponse("name", "Invalid"));
    }

    @Test
    void 업무_예외는_상태_코드_문구를_로그로_남긴다(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/api/v2/test/errors/conflict"));

        assertThat(output).contains("업무 오류 409 CONFLICT: " + ErrorCode.CONFLICT.message());
    }

    @Test
    void 입력값_검증에_실패하면_틀린_항목_이름만_로그로_남긴다(CapturedOutput output) throws Exception {
        // 입력한 값은 비밀번호일 수도 있으므로 남기지 않고, 어떤 항목이 틀렸는지만 남긴다.
        mockMvc.perform(post("/api/v2/test/errors/validation")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"  \", \"memo\": \"typed-secret-value\"}"));

        assertThat(output).contains("입력 오류 400 INVALID_INPUT fields=[name]");
        assertThat(output).doesNotContain("typed-secret-value");
    }

    @Test
    void JSON_형식이_잘못되면_400으로_응답한다() throws Exception {
        mockMvc.perform(post("/api/v2/test/errors/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void 없는_경로는_404로_응답한다() throws Exception {
        mockMvc.perform(get("/api/v2/test/errors/no-such-path"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void 예상하지_못한_예외는_500으로_응답하고_내부_내용을_숨긴다() throws Exception {
        mockMvc.perform(get("/api/v2/test/errors/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value(not(containsString("secret-detail"))));
    }

    @Test
    void 일시_장애는_재시도_가능으로_표시한다() throws Exception {
        mockMvc.perform(get("/api/v2/test/errors/unavailable"))
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

        @GetMapping("/test/errors/count")
        void count(@RequestParam @Min(1) int count) {
        }
    }
}
