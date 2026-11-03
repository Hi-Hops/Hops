package com.hihops.hops.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"db", "test"})
@Transactional
class RegistrationControllerTest {

    private static final UUID DEPARTMENT_ID = UUID.fromString("d0000000-0000-7000-8000-000000000099");
    private static final UUID ADMIN_ID = UUID.fromString("a0000000-0000-7000-8000-000000000099");

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void 부서를_준비한다() {
        jdbcTemplate.update("INSERT INTO department (id, code, name, is_routable) VALUES (?, 'TEST', '테스트', false)",
                DEPARTMENT_ID);
        jdbcTemplate.update("INSERT INTO member (id, login_id, employee_no, password_hash, name, department_id) "
                + "VALUES (?, 'admin', 'ADMIN-1', ?, '관리자', ?)",
                ADMIN_ID, passwordEncoder.encode("admin-test-password"), DEPARTMENT_ID);
        jdbcTemplate.update("INSERT INTO member_role (member_id, role) VALUES (?, 'BIZ_ADMIN')", ADMIN_ID);
    }

    @Test
    void 관리자가_실제_로그인한_토큰으로_등록하고_새_회원이_로그인한다() throws Exception {
        String response = mockMvc.perform(post("/api/v2/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"admin\",\"password\":\"admin-test-password\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(response).get("accessToken").asString();
        mockMvc.perform(post("/api/v2/auth/register").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("new", "EMP-1", "test-password", DEPARTMENT_ID.toString(), "")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v2/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"new\",\"password\":\"test-password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.roles").isEmpty());
    }

    @Test
    void 관리자가_일반_회원을_만들고_비밀번호를_해시로_저장한다() throws Exception {
        mockMvc.perform(post("/api/v2/auth/register").with(jwt().jwt(token -> token.subject(ADMIN_ID.toString()).claim("departmentId", DEPARTMENT_ID.toString()).claim("roles", java.util.List.of("BIZ_ADMIN"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("test.user", "EMP-1", "test-password", DEPARTMENT_ID.toString(),
                                ",\"roles\":[\"BIZ_ADMIN\"]")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.memberId").isNotEmpty());

        String hash = jdbcTemplate.queryForObject("SELECT password_hash FROM member WHERE login_id = ?",
                String.class, "test.user");
        assertThat(hash).isNotEqualTo("test-password");
        assertThat(passwordEncoder.matches("test-password", hash)).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM member_role mr JOIN member m ON m.id = mr.member_id "
                + "WHERE m.login_id = 'test.user'", Integer.class)).isZero();
    }

    @Test
    void 중복된_로그인ID나_사번은_충돌로_거부한다() throws Exception {
        jdbcTemplate.update("INSERT INTO member (login_id, employee_no, password_hash, name, department_id) "
                + "VALUES ('existing', 'EMP-1', 'hash', '기존', ?)", DEPARTMENT_ID);
        mockMvc.perform(post("/api/v2/auth/register").with(jwt().jwt(token -> token.subject(ADMIN_ID.toString()).claim("departmentId", DEPARTMENT_ID.toString()).claim("roles", java.util.List.of("BIZ_ADMIN"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("existing", "EMP-2", "test-password", DEPARTMENT_ID.toString(), "")))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/v2/auth/register").with(jwt().jwt(token -> token.subject(ADMIN_ID.toString()).claim("departmentId", DEPARTMENT_ID.toString()).claim("roles", java.util.List.of("BIZ_ADMIN"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("new", "EMP-1", "test-password", DEPARTMENT_ID.toString(), "")))
                .andExpect(status().isConflict());
    }

    @Test
    void 유효하지_않은_입력과_삭제된_부서를_거부한다() throws Exception {
        mockMvc.perform(post("/api/v2/auth/register").with(jwt().jwt(token -> token.subject(ADMIN_ID.toString()).claim("departmentId", DEPARTMENT_ID.toString()).claim("roles", java.util.List.of("BIZ_ADMIN"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(" ", "EMP-1", "test-password", DEPARTMENT_ID.toString(), "")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v2/auth/register").with(jwt().jwt(token -> token.subject(ADMIN_ID.toString()).claim("departmentId", DEPARTMENT_ID.toString()).claim("roles", java.util.List.of("BIZ_ADMIN"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("new", "EMP-1", "가".repeat(25), DEPARTMENT_ID.toString(), "")))
                .andExpect(status().isBadRequest());
        UUID deletedDepartment = UUID.fromString("d0000000-0000-7000-8000-000000000098");
        jdbcTemplate.update("INSERT INTO department (id, code, name, is_routable, deleted_at) VALUES (?, 'OLD', '삭제', false, now())", deletedDepartment);
        mockMvc.perform(post("/api/v2/auth/register").with(jwt().jwt(token -> token.subject(ADMIN_ID.toString()).claim("departmentId", DEPARTMENT_ID.toString()).claim("roles", java.util.List.of("BIZ_ADMIN"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("new", "EMP-1", "test-password", deletedDepartment.toString(), "")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 인증되지_않거나_관리자_권한이_회수되면_등록을_거부한다() throws Exception {
        String body = request("new", "EMP-1", "test-password", DEPARTMENT_ID.toString(), "");
        mockMvc.perform(post("/api/v2/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        jdbcTemplate.update("UPDATE member_role SET deleted_at = now() WHERE member_id = ?", ADMIN_ID);
        mockMvc.perform(post("/api/v2/auth/register")
                        .with(jwt().jwt(token -> token.subject(ADMIN_ID.toString()).claim("departmentId", DEPARTMENT_ID.toString()).claim("roles", java.util.List.of("BIZ_ADMIN"))))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void 이전_권한이나_소속의_토큰과_일반_회원은_등록할_수_없다() throws Exception {
        String body = request("new", "EMP-1", "test-password", DEPARTMENT_ID.toString(), "");
        mockMvc.perform(post("/api/v2/auth/register")
                .with(jwt().jwt(token -> token.subject(ADMIN_ID.toString()).claim("departmentId", DEPARTMENT_ID.toString()).claim("roles", java.util.List.of())))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v2/auth/register")
                .with(jwt().jwt(token -> token.subject(ADMIN_ID.toString()).claim("departmentId", UUID.randomUUID().toString()).claim("roles", java.util.List.of("BIZ_ADMIN"))))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v2/auth/register")
                .with(jwt().jwt(token -> token.subject(ADMIN_ID.toString())))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        jdbcTemplate.update("UPDATE member_role SET deleted_at = now() WHERE member_id = ?", ADMIN_ID);
        mockMvc.perform(post("/api/v2/auth/register")
                .with(jwt().jwt(token -> token.subject(ADMIN_ID.toString()).claim("departmentId", DEPARTMENT_ID.toString()).claim("roles", java.util.List.of())))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
    }

    private static String request(String login, String employeeNo, String password, String departmentId, String extra) {
        return "{\"loginId\":\"" + login + "\",\"employeeNo\":\"" + employeeNo
                + "\",\"password\":\"" + password + "\",\"name\":\"테스트\",\"departmentId\":\""
                + departmentId + "\"" + extra + "}";
    }
}
