package com.hihops.hops.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Spring Security 기본 설정을 확인한다: 주소별 접근 규칙, 401·403 공통 오류 응답, CORS, 프록시 HTTPS 헤더.
 * 여기서는 주소 규칙만 보므로 로그인한 상태는 @WithMockUser(테스트용 가짜 사용자)로 흉내 낸다. 실제 JWT 검증은 JwtAuthenticationTest에서 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"db", "test"})
@Import(SecurityConfigTest.TestSecurityController.class)
class SecurityConfigTest {

    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

    @Test
    void 임시_계정과_비밀번호를_자동으로_만들지_않는다() {
        assertThat(context.getBeansOfType(UserDetailsService.class)).isEmpty();
    }

    @Test
    void 로그인하지_않고_API를_호출하면_401_공통_오류로_응답한다() throws Exception {
        mockMvc.perform(get("/api/tickets"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.requestId").value(matchesPattern(UUID_PATTERN)));
    }

    @Test
    void 로그인_API는_로그인_없이_접근할_수_있다() throws Exception {
        // 본문이 없어 400(입력 오류)이지만, 401로 막히지 않고 로그인 API까지 도달했다는 것이 핵심이다.
        mockMvc.perform(post("/api/auth/login"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void 로그인한_사용자는_API에_접근할_수_있다() throws Exception {
        mockMvc.perform(get("/test/security/scheme"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser
    void 권한이_없으면_403_공통_오류로_응답한다() throws Exception {
        mockMvc.perform(get("/test/security/denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void 허용한_프론트_주소의_CORS_요청은_통과한다() throws Exception {
        mockMvc.perform(options("/api/tickets")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"));
    }

    @Test
    void 프론트_README_주소에서_쿠키를_포함한_로그인_요청을_허용한다() throws Exception {
        // 프론트 README는 http://127.0.0.1:5173으로 접속하라고 안내한다. 브라우저는 localhost와 127.0.0.1을 다른 주소로 본다.
        // 로그인·refresh·로그아웃은 credentials: include(쿠키 포함)로 보내므로 Allow-Credentials가 있어야 브라우저가 응답을 넘겨준다
        // (WIRE_CONTRACT 9·10절).
        mockMvc.perform(options("/api/auth/login")
                        .header(HttpHeaders.ORIGIN, "http://127.0.0.1:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://127.0.0.1:5173"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    void 허용하지_않은_주소의_CORS_요청은_거부한다() throws Exception {
        mockMvc.perform(options("/api/tickets")
                        .header(HttpHeaders.ORIGIN, "http://evil.example.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    @WithMockUser
    void 프록시가_알려준_HTTPS를_원래_요청_방식으로_인식한다() throws Exception {
        mockMvc.perform(get("/test/security/scheme").header("X-Forwarded-Proto", "https"))
                .andExpect(content().string("https"));
    }

    @TestConfiguration
    @RestController
    static class TestSecurityController {

        @GetMapping("/test/security/scheme")
        String scheme(HttpServletRequest request) {
            return request.getScheme();
        }

        @GetMapping("/test/security/denied")
        void denied() {
            throw new AccessDeniedException("권한 없음");
        }
    }
}
