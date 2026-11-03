package com.hihops.hops.demo;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"db", "test"})
@Transactional
class DemoSeedIntegrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MockMvc mockMvc;

    @Test
    void 개발용_기본_seed와_함께_문의_상세와_대화_및_소유권을_조회한다() throws Exception {
        executeSql("db/seed/R__seed_dev_department_member.sql");
        executeSql("db/seed/R__seed_dev_it_system.sql");
        executeSql("db/demo/hops_system_1_demo.sql");
        String requester = "a0000000-0000-7000-8000-000000000001";
        String other = "a0000000-0000-7000-8000-000000000014";
        mockMvc.perform(get("/api/v2/inquiries").with(jwt().jwt(token -> token.subject(requester))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(4));
        for (int n = 1; n <= 4; n++) {
            String inquiryId = "10000000-0000-7000-8000-" + String.format("%012d", n);
            mockMvc.perform(get("/api/v2/inquiries/" + inquiryId).with(jwt().jwt(token -> token.subject(requester))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.problems").isEmpty());
            mockMvc.perform(get("/api/v2/inquiries/" + inquiryId + "/messages").with(jwt().jwt(token -> token.subject(requester))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].sender").value("USER"));
            mockMvc.perform(get("/api/v2/inquiries/" + inquiryId).with(jwt().jwt(token -> token.subject(other))))
                    .andExpect(status().isNotFound());
        }
        mockMvc.perform(get("/api/v2/inquiries").with(jwt().jwt(token -> token.subject(other))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/v2/inquiries/10000000-0000-7000-8000-000000000005")
                .with(jwt().jwt(token -> token.subject(other)))).andExpect(status().isOk());
    }

    @Test
    void 기존_시연_계정만_있는_환경도_사용한다() throws Exception {
        executeSql("db/seed/R__seed_dev_department_member.sql");
        executeSql("db/seed/R__seed_dev_it_system.sql");
        jdbcTemplate.update("DELETE FROM member WHERE login_id IN ('requester', '20260014')");
        jdbcTemplate.update("INSERT INTO member (id, login_id, employee_no, password_hash, name, department_id) "
                + "SELECT 'f5b994d0-b857-5aba-bd46-2c49c7e8d1f0', 'demo.requester', 'DEMO-1', password_hash, '가상 문의자', department_id FROM member WHERE login_id = 'bizadmin'");
        jdbcTemplate.update("INSERT INTO member (id, login_id, employee_no, password_hash, name, department_id) "
                + "SELECT 'ca1fd3bb-10f4-5504-94da-704b8a5f394b', 'demo.file', 'DEMO-2', password_hash, '가상 담당자', department_id FROM member WHERE login_id = 'bizadmin'");
        executeSql("db/demo/hops_system_1_demo.sql");
        mockMvc.perform(get("/api/v2/inquiries").with(jwt().jwt(token -> token.subject("f5b994d0-b857-5aba-bd46-2c49c7e8d1f0"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(4));
        mockMvc.perform(get("/api/v2/inquiries/10000000-0000-7000-8000-000000000005")
                .with(jwt().jwt(token -> token.subject("ca1fd3bb-10f4-5504-94da-704b8a5f394b"))))
                .andExpect(status().isOk());
    }

    private void executeSql(String path) throws Exception {
        String sql = new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        // 테스트의 트랜잭션으로 묶어 각 seed와 함께 롤백한다.
        jdbcTemplate.execute(sql.replace("\r\n", "\n")
                .replace("\nBEGIN;\n", "\n").replace("\nCOMMIT;\n", "\n"));
    }
}
