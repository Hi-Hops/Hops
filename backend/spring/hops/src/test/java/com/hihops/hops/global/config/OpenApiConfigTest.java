package com.hihops.hops.global.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Swagger 화면과 API 설명 데이터(/v3/api-docs)가 로그인 없이 열리고, JWT 입력 칸(bearerAuth)이 있는지 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class OpenApiConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void API_설명_데이터를_로그인_없이_볼_수_있다() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Hops API"));
    }

    @Test
    void JWT_입력_칸이_있다() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"));
    }

    @Test
    void Swagger_화면을_로그인_없이_열_수_있다() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }
}
