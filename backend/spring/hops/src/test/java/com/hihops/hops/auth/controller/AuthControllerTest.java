package com.hihops.hops.auth.controller;

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

    @BeforeEach
    void 테스트용_회원을_넣는다() {
        jdbcTemplate.update("INSERT INTO department (id, code, name, is_routable) VALUES (?, 'TEST_SALES', '테스트 영업팀', false)",
                DEPARTMENT_ID);
        jdbcTemplate.update("INSERT INTO member (login_id, employee_no, password_hash, name, department_id) "
                + "VALUES ('requester', '20260001', ?, '테스트 요청자', ?)", passwordEncoder.encode("hops1234!"), DEPARTMENT_ID);
    }

    @Test
    void 로그인ID와_비밀번호가_맞으면_200과_사번을_포함한_회원_정보를_반환한다() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId": "requester", "password": "hops1234!"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").isNotEmpty())
                .andExpect(jsonPath("$.employeeNo").value("20260001"))
                .andExpect(jsonPath("$.displayName").value("테스트 요청자"));
    }
}
