package com.hihops.hops.itsystem.controller;

import static org.hamcrest.Matchers.contains;
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
 * 시스템 목록 API(GET /api/v2/systems)가 삭제되지 않은 시스템과 기능만, 담당 정보 없이 돌려주는지 확인한다
 * (WIRE_CONTRACT 12.2). 테스트 DB에는 개발용 시드가 없어 필요한 행을 직접 넣고, 테스트가 끝나면 되돌려진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"db", "test"})
@Transactional
class SystemControllerTest {

    private static final UUID DEPARTMENT_ID = UUID.fromString("d0000000-0000-7000-8000-000000000099");
    private static final UUID MEMBER_ID = UUID.fromString("a0000000-0000-7000-8000-000000000099");
    private static final UUID APPROVAL_ID = UUID.fromString("3320ce84-c47c-592c-b599-6e968e12b0a3");
    private static final UUID LOGIN_ID = UUID.fromString("8ae35be5-1857-58c0-a478-e05b4217ff95");
    private static final UUID FILE_ID = UUID.fromString("22d935d0-2ed8-58a7-a9b8-d73f717ed3c4");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 테스트용_시스템을_넣는다() {
        jdbcTemplate.update("INSERT INTO department (id, code, name) VALUES (?, 'TEST_IT', '테스트 IT팀')", DEPARTMENT_ID);
        jdbcTemplate.update("INSERT INTO member (id, login_id, employee_no, password_hash, name, department_id) "
                + "VALUES (?, 'test.owner', '29990099', 'hash', '테스트 담당자', ?)", MEMBER_ID, DEPARTMENT_ID);
        insertSystem(APPROVAL_ID, "ELECTRONIC_APPROVAL", "전자결재", false);
        insertSystem(LOGIN_ID, "COMMON_LOGIN", "공통 로그인", false);
        insertSystem(FILE_ID, "FILE_SHARING", "파일 공유", true); // 삭제된 시스템
        insertFeature(APPROVAL_ID, "DOCUMENT_VIEW", "문서 조회", false);
        insertFeature(APPROVAL_ID, "DOCUMENT_CREATE", "문서 작성", false);
        insertFeature(APPROVAL_ID, "ATTACHMENT_VIEW", "첨부 열람", true); // 삭제된 기능
        insertFeature(FILE_ID, "FILE_VIEW", "파일 열람", false);
    }

    @Test
    void 삭제되지_않은_시스템과_기능을_코드_순으로_돌려준다() throws Exception {
        mockMvc.perform(get("/api/v2/systems").with(jwt().jwt(token -> token.subject(MEMBER_ID.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code").value(contains("COMMON_LOGIN", "ELECTRONIC_APPROVAL")))
                // 기능이 없는 시스템도 키를 생략하지 않고 빈 배열이다
                .andExpect(jsonPath("$[0].id").value(LOGIN_ID.toString()))
                .andExpect(jsonPath("$[0].name").value("공통 로그인"))
                .andExpect(jsonPath("$[0].features").isArray())
                .andExpect(jsonPath("$[0].features").isEmpty())
                .andExpect(jsonPath("$[1].features[*].name").value(contains("문서 작성", "문서 조회")))
                // 계약 필드만 보낸다. 주담당자·기능 코드·설명은 직원 목록에 넣지 않는다
                .andExpect(jsonPath("$[1].primaryUser").doesNotExist())
                .andExpect(jsonPath("$[1].description").doesNotExist())
                .andExpect(jsonPath("$[1].features[0].code").doesNotExist());
    }

    @Test
    void 로그인하지_않으면_401이다() throws Exception {
        mockMvc.perform(get("/api/v2/systems"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    private void insertSystem(UUID id, String code, String name, boolean deleted) {
        jdbcTemplate.update("INSERT INTO it_system (id, code, name, primary_owner_member_id, deleted_at) "
                + "VALUES (?, ?, ?, ?, " + (deleted ? "now()" : "NULL") + ")", id, code, name, MEMBER_ID);
    }

    private void insertFeature(UUID systemId, String code, String name, boolean deleted) {
        jdbcTemplate.update("INSERT INTO system_feature (it_system_id, code, name, deleted_at) "
                + "VALUES (?, ?, ?, " + (deleted ? "now()" : "NULL") + ")", systemId, code, name);
    }
}
