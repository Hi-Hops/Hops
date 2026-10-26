package com.hihops.hops.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 로그인 API를 실제 테스트 DB(hops_test)의 회원으로 호출한다. 넣은 행은 테스트가 끝나면 되돌려진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"db", "test"})
@Transactional
class AuthControllerTest {

    private static final UUID DEPARTMENT_ID = UUID.fromString("d0000000-0000-7000-8000-000000000099");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void 테스트용_회원을_넣는다() {
        jdbcTemplate.update("INSERT INTO department (id, code, name, is_routable) VALUES (?, 'TEST_SALES', '테스트 영업팀', false)",
                DEPARTMENT_ID);
        jdbcTemplate.update("INSERT INTO member (login_id, employee_no, password_hash, name, department_id) "
                + "VALUES ('requester', '20260001', ?, '테스트 요청자', ?)", passwordEncoder.encode("hops1234!"), DEPARTMENT_ID);
    }

    @Test
    void 로그인ID와_비밀번호가_맞으면_200과_사번을_포함한_회원_정보를_반환한다() throws Exception {
        // 응답 모양은 계약(specs/contracts/DATA_TYPES.md LoginResponse·CurrentUser)을 따른다.
        mockMvc.perform(login("requester", "hops1234!"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").isNotEmpty())
                .andExpect(jsonPath("$.user.employeeNo").value("20260001"))
                .andExpect(jsonPath("$.user.name").value("테스트 요청자"));
    }

    @Test
    void 로그인하면_이_서버가_검증할_수_있는_15분짜리_액세스_토큰을_발급한다() throws Exception {
        String body = mockMvc.perform(login("requester", "hops1234!"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode response = objectMapper.readTree(body);

        // 서버의 실제 검증기로 연다: 서명·만료·발급자·대상이 하나라도 틀리면 여기서 예외가 난다.
        Jwt token = jwtDecoder.decode(response.get("accessToken").asString());

        assertThat(token.getSubject()).isEqualTo(response.get("user").get("id").asString());
        assertThat(token.getClaimAsString("employeeNo")).isEqualTo("20260001");
        assertThat(Duration.between(token.getIssuedAt(), token.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(Instant.parse(response.get("expiresAt").asString())).isEqualTo(token.getExpiresAt());
    }

    @Test
    void 토큰과_사용자_정보에_소속_부서를_담는다() throws Exception {
        // 계약: JWT departmentId·departmentCode·departmentName (docs/API.md §2.5), user.department (DATA_TYPES.md Department)
        String body = mockMvc.perform(login("requester", "hops1234!"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.department.id").value(DEPARTMENT_ID.toString()))
                .andExpect(jsonPath("$.user.department.code").value("TEST_SALES"))
                .andExpect(jsonPath("$.user.department.name").value("테스트 영업팀"))
                .andExpect(jsonPath("$.user.department.isRoutable").value(false))
                .andReturn().getResponse().getContentAsString();

        Jwt token = jwtDecoder.decode(objectMapper.readTree(body).get("accessToken").asString());

        assertThat(token.getClaimAsString("departmentId")).isEqualTo(DEPARTMENT_ID.toString());
        assertThat(token.getClaimAsString("departmentCode")).isEqualTo("TEST_SALES");
        assertThat(token.getClaimAsString("departmentName")).isEqualTo("테스트 영업팀");
    }

    private static MockHttpServletRequestBuilder login(String loginId, String password) {
        return post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\": \"%s\", \"password\": \"%s\"}".formatted(loginId, password));
    }

    @Test
    void 비밀번호가_틀리면_401과_공통_오류_형식으로_응답한다() throws Exception {
        // 프론트 계약(specs/contracts): 로그인 실패는 401 UNAUTHENTICATED. 아이디가 없는지 비밀번호가 틀렸는지는 알려주지 않는다.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId": "requester", "password": "wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.message").value("아이디 또는 비밀번호를 확인해 주세요."))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void 로그인ID가_비어_있으면_DB를_조회하지_않고_400으로_응답한다() throws Exception {
        // 계약(specs/contracts/DATA_TYPES.md LoginRequest): loginId·password는 최소 1글자.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId": "", "password": "hops1234!"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }
}
