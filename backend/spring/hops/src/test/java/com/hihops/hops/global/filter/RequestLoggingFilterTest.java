package com.hihops.hops.global.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * API 호출 전·후 로그의 형식과, 비밀 값(JWT·쿼리)이 로그에 남지 않는지 확인한다.
 * CapturedOutput은 테스트 중 콘솔에 찍힌 로그를 문자열로 받아 온다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"db", "test"})
@WithMockUser // 이 테스트는 오류·로그 형식을 보므로 로그인한 상태로 실행한다 (인증 규칙은 SecurityConfigTest).
@ExtendWith(OutputCaptureExtension.class)
class RequestLoggingFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 요청_전후로_메서드_경로_상태_소요시간을_남긴다(CapturedOutput output) throws Exception {
        String requestId = mockMvc.perform(get("/api/logging-check"))
                .andReturn().getResponse().getHeader(RequestIdFilter.HEADER);

        assertThat(output).contains("→ GET /api/logging-check");
        assertThat(output).containsPattern("← GET /api/logging-check 404 \\(\\d+ms\\)");
        assertThat(output).contains("requestId=" + requestId);
    }

    @Test
    void 인증_헤더와_쿼리_문자열은_남기지_않는다(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/api/logging-check?password=secret-query")
                .header("Authorization", "Bearer secret-token"));

        assertThat(output).contains("→ GET /api/logging-check");
        assertThat(output).doesNotContain("secret-token");
        assertThat(output).doesNotContain("secret-query");
    }
}
