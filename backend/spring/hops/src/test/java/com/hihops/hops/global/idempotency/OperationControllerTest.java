package com.hihops.hops.global.idempotency;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 작업 결과 재확인 API(GET /api/v2/operations)가 계약 형식의 CommandReceipt를 본인에게만 돌려주는지 확인한다
 * (WIRE_CONTRACT 12.2). 넣은 행은 테스트가 끝나면 되돌려진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"db", "test"})
@Transactional
class OperationControllerTest {

    private static final UUID DEPARTMENT_ID = UUID.fromString("d0000000-0000-7000-8000-000000000099");
    private static final UUID MEMBER_ID = UUID.fromString("a0000000-0000-7000-8000-000000000099");
    private static final UUID OTHER_MEMBER_ID = UUID.fromString("a0000000-0000-7000-8000-000000000098");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    record SampleCommand(UUID requestId, String content) {
    }

    @BeforeEach
    void 테스트용_요청자를_넣는다() {
        jdbcTemplate.update("INSERT INTO department (id, code, name) VALUES (?, 'TEST_SALES', '테스트 영업팀')", DEPARTMENT_ID);
        jdbcTemplate.update("INSERT INTO member (id, login_id, employee_no, password_hash, name, department_id) "
                + "VALUES (?, 'test.requester', '29990099', 'hash', '테스트 문의자', ?)", MEMBER_ID, DEPARTMENT_ID);
        jdbcTemplate.update("INSERT INTO member (id, login_id, employee_no, password_hash, name, department_id) "
                + "VALUES (?, 'test.other', '29990098', 'hash', '다른 문의자', ?)", OTHER_MEMBER_ID, DEPARTMENT_ID);
    }

    @Test
    void 요청_키와_작업_번호로_같은_영수증을_조회한다() throws Exception {
        UUID requestId = UUID.randomUUID();
        UUID inquiryId = UUID.randomUUID();
        CommandReceipt receipt = idempotencyService.execute(
                new IdempotentRequest(MEMBER_ID, requestId, "POST", "/api/v2/inquiries",
                        new SampleCommand(requestId, "문의")),
                operationId -> new ResourceRef(ResourceType.INQUIRY, inquiryId)).receipt();

        for (String path : new String[] {"/api/v2/operations/by-request/" + requestId,
                "/api/v2/operations/" + receipt.operationId()}) {
            mockMvc.perform(get(path).with(jwt().jwt(token -> token.subject(MEMBER_ID.toString()))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.requestId").value(requestId.toString()))
                    .andExpect(jsonPath("$.operationId").value(receipt.operationId().toString()))
                    .andExpect(jsonPath("$.state").value("SUCCEEDED"))
                    .andExpect(jsonPath("$.resource.type").value("INQUIRY"))
                    .andExpect(jsonPath("$.resource.id").value(inquiryId.toString()))
                    // 계약상 null 값도 키를 생략하지 않는다
                    .andExpect(jsonPath("$.error").hasJsonPath())
                    .andExpect(jsonPath("$.error").isEmpty());
        }
    }

    @Test
    void 다른_사람의_작업이나_없는_요청은_404로_구분하지_않는다() throws Exception {
        UUID requestId = UUID.randomUUID();
        CommandReceipt receipt = idempotencyService.execute(
                new IdempotentRequest(MEMBER_ID, requestId, "POST", "/api/v2/inquiries",
                        new SampleCommand(requestId, "문의")),
                operationId -> new ResourceRef(ResourceType.INQUIRY, UUID.randomUUID())).receipt();

        mockMvc.perform(get("/api/v2/operations/" + receipt.operationId())
                        .with(jwt().jwt(token -> token.subject(OTHER_MEMBER_ID.toString()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(get("/api/v2/operations/by-request/" + UUID.randomUUID())
                        .with(jwt().jwt(token -> token.subject(MEMBER_ID.toString()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void 로그인하지_않으면_401이다() throws Exception {
        mockMvc.perform(get("/api/v2/operations/by-request/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
}
