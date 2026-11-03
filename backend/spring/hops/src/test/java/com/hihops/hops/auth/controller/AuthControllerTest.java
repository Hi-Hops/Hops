package com.hihops.hops.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.annotation.Transactional;

import com.hihops.hops.auth.service.AuthService;
import com.hihops.hops.auth.service.RefreshTokenService;
import com.hihops.hops.realtime.service.RealtimeService;

import jakarta.servlet.http.Cookie;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 로그인 API를 실제 테스트 DB(hops_test)의 회원으로 호출한다. 넣은 행은 테스트가 끝나면 되돌려진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"db", "test"})
@Transactional
@ExtendWith(OutputCaptureExtension.class) // CapturedOutput: 테스트 중 찍힌 로그를 문자열로 받아 검사한다
class AuthControllerTest {

    private static final UUID DEPARTMENT_ID = UUID.fromString("d0000000-0000-7000-8000-000000000099");
    private static final UUID MEMBER_ID = UUID.fromString("a0000000-0000-7000-8000-000000000099");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 열린 SSE 종료는 RealtimeServiceTest가 확인한다. 여기서는 로그아웃이 그 회원으로 종료를 부르는지만 본다.
    @MockitoBean
    private RealtimeService realtimeService;

    // 진짜 동작을 그대로 쓰되, 장애 상황 테스트에서만 특정 호출이 실패하도록 바꾼다.
    @MockitoSpyBean
    private AuthService authService;

    @MockitoSpyBean
    private RefreshTokenService refreshTokenService;

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
        jdbcTemplate.update("INSERT INTO member (id, login_id, employee_no, password_hash, name, department_id) "
                + "VALUES (?, 'requester', '20260001', ?, '테스트 요청자', ?)",
                MEMBER_ID, passwordEncoder.encode("hops1234!"), DEPARTMENT_ID);
    }

    @Test
    void 로그인ID와_비밀번호가_맞으면_200과_사번을_포함한_회원_정보를_반환한다() throws Exception {
        // 응답 모양은 계약(specs/contracts/DATA_TYPES.md LoginResponse·CurrentUser)을 따른다.
        mockMvc.perform(login("requester", "hops1234!"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").isNotEmpty())
                .andExpect(jsonPath("$.user.employeeNo").value("20260001"))
                .andExpect(jsonPath("$.user.name").value("테스트 요청자"))
                // 역할이 없으면 빈 배열이다. 계약상 키를 생략하지 않는다.
                .andExpect(jsonPath("$.user.roles").isArray())
                .andExpect(jsonPath("$.user.roles").isEmpty());
    }

    @Test
    void 부여된_역할을_토큰과_사용자_정보에_담는다() throws Exception {
        jdbcTemplate.update("INSERT INTO member_role (member_id, role) VALUES (?, 'BIZ_ADMIN')", MEMBER_ID);

        String body = mockMvc.perform(login("requester", "hops1234!"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.roles[0]").value("BIZ_ADMIN"))
                .andReturn().getResponse().getContentAsString();

        Jwt token = jwtDecoder.decode(objectMapper.readTree(body).get("accessToken").asString());

        assertThat(token.getClaimAsStringList("roles")).containsExactly("BIZ_ADMIN");
    }

    @Test
    void 회수된_역할은_토큰에_넣지_않는다() throws Exception {
        // 역할 회수는 행을 지우지 않고 deleted_at만 기록한다(ERD). 회수된 권한이 토큰에 남으면 안 된다.
        jdbcTemplate.update("INSERT INTO member_role (member_id, role) VALUES (?, 'BIZ_ADMIN')", MEMBER_ID);
        jdbcTemplate.update("INSERT INTO member_role (member_id, role, deleted_at) VALUES (?, 'AGENT_OPS_ADMIN', now())",
                MEMBER_ID);

        String body = mockMvc.perform(login("requester", "hops1234!"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.roles.length()").value(1))
                .andReturn().getResponse().getContentAsString();

        Jwt token = jwtDecoder.decode(objectMapper.readTree(body).get("accessToken").asString());

        assertThat(token.getClaimAsStringList("roles")).containsExactly("BIZ_ADMIN");
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
    void 로그인하면_HttpOnly_Refresh_쿠키를_함께_내려주고_원문은_JSON에_넣지_않는다() throws Exception {
        // F001-AC05·WIRE_CONTRACT 10절: Refresh는 HttpOnly 쿠키, 최초 로그인부터 7일. DATA_TYPES: 원문은 JSON에 없음.
        MockHttpServletResponse response = mockMvc.perform(login("requester", "hops1234!"))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        String cookie = refreshCookie(response);
        String value = cookie.substring("hops_refresh=".length(), cookie.indexOf(';'));
        assertThat(value).isNotBlank();
        assertThat(cookie)
                .contains("HttpOnly") // 프론트 JS가 읽지 못한다 (XSS로 훔칠 수 없음)
                .contains("SameSite=Lax")
                .contains("Path=/api/v2/auth") // 로그인·refresh·로그아웃 요청에만 따라간다
                .contains("Max-Age=" + Duration.ofDays(7).toSeconds());
        assertThat(response.getContentAsString()).doesNotContain(value);
    }

    @Test
    void 로컬_개발_설정에서는_http라도_Secure_없이_쿠키를_준다() throws Exception {
        // 로컬 개발처럼 hops.auth.refresh-cookie.secure=false (application-test.properties). 운영은 설정이 없어 기본값 true다.
        String cookie = refreshCookie(mockMvc.perform(login("requester", "hops1234!")).andReturn().getResponse());

        assertThat(cookie).doesNotContain("Secure");
    }

    @Test
    void HTTPS로_들어와도_Secure는_요청이_아니라_설정으로_정한다() throws Exception {
        // 프록시 설정에 따라 결과가 달라지지 않게 한다. 로컬 설정(false)에서는 https 요청이어도 Secure를 붙이지 않는다.
        String cookie = refreshCookie(mockMvc.perform(login("requester", "hops1234!").secure(true)).andReturn().getResponse());

        assertThat(cookie).doesNotContain("Secure");
    }

    @Test
    void Refresh_쿠키로_갱신하면_새_액세스_토큰과_회전된_Refresh_쿠키를_받는다() throws Exception {
        // WIRE_CONTRACT 2절: POST /api/v2/auth/refresh 본문 없음·Refresh 쿠키 → 200 LoginResponse
        // WIRE_CONTRACT 10절: 갱신 때 회전(새 값)한다. DATA_TYPES: Refresh 원문은 JSON에 넣지 않는다.
        String oldRefresh = refreshValue(mockMvc.perform(login("requester", "hops1234!")).andReturn().getResponse());

        MockHttpServletResponse response = mockMvc.perform(refresh(oldRefresh))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(MEMBER_ID.toString()))
                .andExpect(jsonPath("$.user.employeeNo").value("20260001"))
                .andReturn().getResponse();

        Jwt token = jwtDecoder.decode(objectMapper.readTree(response.getContentAsString()).get("accessToken").asString());
        assertThat(token.getSubject()).isEqualTo(MEMBER_ID.toString());

        String newRefresh = refreshValue(response);
        assertThat(newRefresh).isNotBlank().isNotEqualTo(oldRefresh);
        assertThat(refreshCookie(response)).contains("HttpOnly").contains("SameSite=Lax").contains("Path=/api/v2/auth");
        assertThat(response.getContentAsString()).doesNotContain(newRefresh);
    }

    @Test
    void 로그아웃하면_204와_쿠키_삭제를_받고_그_Refresh로는_다시_갱신할_수_없다() throws Exception {
        // WIRE_CONTRACT 12.2: POST /api/v2/auth/logout 본문 없음·cookie → 204. 10절: 현재 로그인 세션을 폐기한다.
        String refresh = refreshValue(mockMvc.perform(login("requester", "hops1234!")).andReturn().getResponse());

        MockHttpServletResponse response = mockMvc.perform(logout(refresh))
                .andExpect(status().isNoContent())
                .andReturn().getResponse();

        // 같은 이름·경로에 Max-Age=0을 보내면 브라우저가 쿠키를 지운다.
        assertThat(refreshCookie(response)).startsWith("hops_refresh=;").contains("Max-Age=0").contains("Path=/api/v2/auth");
        mockMvc.perform(refresh(refresh))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REVOKED"));
    }

    @Test
    void 쿠키_없이_다시_로그아웃해도_204와_쿠키_삭제로_응답한다() throws Exception {
        // WIRE_CONTRACT 12.2: "반복 시에도 쿠키 삭제". 이미 끝난 세션을 오류로 돌려주지 않는다.
        MockHttpServletResponse response = mockMvc.perform(post("/api/v2/auth/logout").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isNoContent())
                .andReturn().getResponse();

        assertThat(refreshCookie(response)).startsWith("hops_refresh=;").contains("Max-Age=0");
    }

    @Test
    void 허용하지_않은_Origin의_갱신과_로그아웃은_403이고_세션은_그대로다() throws Exception {
        // WIRE_CONTRACT 12.1: refresh/logout은 허용 Origin 검증, 실패 시 403 ORIGIN_DENIED (다른 사이트가 쿠키로 몰래 부르는 것을 막는다)
        String refresh = refreshValue(mockMvc.perform(login("requester", "hops1234!")).andReturn().getResponse());

        for (String path : new String[] {"/api/v2/auth/refresh", "/api/v2/auth/logout"}) {
            mockMvc.perform(post(path).header(HttpHeaders.ORIGIN, "https://evil.example")
                            .cookie(new Cookie("hops_refresh", refresh)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ORIGIN_DENIED"));
            // Origin이 없는 요청도 출처를 확인할 수 없으므로 막는다.
            mockMvc.perform(post(path).cookie(new Cookie("hops_refresh", refresh)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ORIGIN_DENIED"));
        }

        // 막힌 요청은 쿠키를 쓰지도 세션을 끝내지도 않았다.
        mockMvc.perform(refresh(refresh)).andExpect(status().isOk());
    }

    @Test
    void 로그아웃하면_그_회원의_열린_SSE를_닫는다() throws Exception {
        String refresh = refreshValue(mockMvc.perform(login("requester", "hops1234!")).andReturn().getResponse());

        mockMvc.perform(logout(refresh)).andExpect(status().isNoContent());

        verify(realtimeService).disconnect(MEMBER_ID);
    }

    @Test
    void 갱신_중_회원_조회가_일시_실패해도_같은_Refresh로_다시_갱신할_수_있다() throws Exception {
        // 회원 확인을 먼저 하고 회전은 나중에 한다. 순서가 반대면 DB 장애 한 번에 쿠키의 Refresh가 이미 쓰인 값이 된다.
        String refresh = refreshValue(mockMvc.perform(login("requester", "hops1234!")).andReturn().getResponse());
        doThrow(new CannotCreateTransactionException("DB 일시 장애")).when(authService).currentMember(MEMBER_ID);

        mockMvc.perform(refresh(refresh)).andExpect(status().is5xxServerError());

        reset(authService);
        mockMvc.perform(refresh(refresh)).andExpect(status().isOk());
    }

    @Test
    void 삭제된_회원이_갱신하면_재로그인_코드로_거절하고_세션을_끝낸다() throws Exception {
        String refresh = refreshValue(mockMvc.perform(login("requester", "hops1234!")).andReturn().getResponse());
        jdbcTemplate.update("UPDATE member SET deleted_at = now() WHERE id = ?", MEMBER_ID);

        mockMvc.perform(refresh(refresh))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REVOKED"));
        verify(realtimeService).disconnect(MEMBER_ID);
    }

    @Test
    void 세션_저장소_장애_중에도_로그아웃은_쿠키를_지우고_실패는_503으로_알린다() throws Exception {
        // 프론트는 HttpOnly 쿠키를 직접 지울 수 없다. 서버가 지우지 않으면 장애가 끝난 뒤 그 쿠키로 세션이 되살아난다.
        // 서버 세션은 끝내지 못했으므로 204(성공)라고 하지 않는다.
        doThrow(new RedisConnectionFailureException("Redis 장애")).when(refreshTokenService).revoke(anyString());

        MockHttpServletResponse response = mockMvc.perform(logout("any-refresh"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andReturn().getResponse();

        assertThat(refreshCookie(response)).startsWith("hops_refresh=;").contains("Max-Age=0");
    }

    @Test
    void 저장된_값이_깨져_예외가_나도_로그아웃은_쿠키를_지운다() throws Exception {
        // WIRE_CONTRACT 12.2 "반복 시에도 쿠키 삭제". Redis 장애뿐 아니라 어떤 실패에도 쿠키 삭제 헤더는 나가야 한다.
        doThrow(new IllegalArgumentException("손상된 Refresh 값")).when(refreshTokenService).revoke(anyString());

        MockHttpServletResponse response = mockMvc.perform(logout("any-refresh"))
                .andExpect(status().isServiceUnavailable())
                .andReturn().getResponse();

        assertThat(refreshCookie(response)).startsWith("hops_refresh=;").contains("Max-Age=0");
    }

    @Test
    void 예전_쿠키_정리가_실패해도_로그인은_성공한다() throws Exception {
        // 예전 세션 정리는 덤이다. 손상된 예전 쿠키 때문에 로그인이 계속 500이면, 프론트는 HttpOnly 쿠키를 지울 수 없어 갇힌다.
        doThrow(new IllegalArgumentException("손상된 Refresh 값")).when(refreshTokenService).revoke(anyString());

        mockMvc.perform(login("requester", "hops1234!").cookie(new Cookie("hops_refresh", "broken")))
                .andExpect(status().isOk());
    }

    @Test
    void 인코딩한_주소로_보내도_Origin_검사를_건너뛸_수_없다() throws Exception {
        // %72 = r. 서버는 /api/v2/auth/refresh로 해석하므로 Origin 검사도 같은 기준으로 적용돼야 한다.
        String refresh = refreshValue(mockMvc.perform(login("requester", "hops1234!")).andReturn().getResponse());

        mockMvc.perform(post(URI.create("/api/v2/auth/%72efresh")).cookie(new Cookie("hops_refresh", refresh)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORIGIN_DENIED"));
    }

    @Test
    void 같은_브라우저에서_다시_로그인하면_예전_쿠키의_세션을_끝낸다() throws Exception {
        // 쿠키만 덮어쓰면 예전 세션이 7일 동안 남는다. 복사해 둔 예전 쿠키로 계속 갱신할 수 있으면 안 된다.
        String oldRefresh = refreshValue(mockMvc.perform(login("requester", "hops1234!")).andReturn().getResponse());

        mockMvc.perform(login("requester", "hops1234!").cookie(new Cookie("hops_refresh", oldRefresh)))
                .andExpect(status().isOk());

        mockMvc.perform(refresh(oldRefresh))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REVOKED"));
    }

    @Test
    void 토큰과_사용자_정보에_소속_부서를_담는다() throws Exception {
        // 계약: JWT departmentId·departmentCode·departmentName (docs/API.md §2.5), user.department (DATA_TYPES.md Department)
        String body = mockMvc.perform(login("requester", "hops1234!"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.department.id").value(DEPARTMENT_ID.toString()))
                .andExpect(jsonPath("$.user.department.code").value("TEST_SALES"))
                .andExpect(jsonPath("$.user.department.name").value("테스트 영업팀"))
                // DATA_TYPES 신규 절 Department: id·code·name. isRoutable은 신규 응답에서 제외한다.
                .andExpect(jsonPath("$.user.department.isRoutable").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        Jwt token = jwtDecoder.decode(objectMapper.readTree(body).get("accessToken").asString());

        assertThat(token.getClaimAsString("departmentId")).isEqualTo(DEPARTMENT_ID.toString());
        assertThat(token.getClaimAsString("departmentCode")).isEqualTo("TEST_SALES");
        assertThat(token.getClaimAsString("departmentName")).isEqualTo("테스트 영업팀");
    }

    @Test
    void 주담당자인_시스템_ID를_primarySystemIds에_담고_삭제된_시스템은_뺀다() throws Exception {
        // DATA_TYPES 신규 절 CurrentUser.primarySystemIds: 담당 메뉴용 스냅샷. 프론트는 이 키가 없으면 로그인을 멈춘다.
        UUID approval = UUID.fromString("c0000000-0000-7000-8000-000000000091");
        UUID fileSharing = UUID.fromString("c0000000-0000-7000-8000-000000000092");
        jdbcTemplate.update("INSERT INTO it_system (id, code, name, primary_owner_member_id) VALUES (?, 'ELECTRONIC_APPROVAL', '전자결재', ?)",
                approval, MEMBER_ID);
        jdbcTemplate.update("INSERT INTO it_system (id, code, name, primary_owner_member_id, deleted_at) "
                + "VALUES (?, 'FILE_SHARING', '파일 공유', ?, now())", fileSharing, MEMBER_ID);

        String body = mockMvc.perform(login("requester", "hops1234!"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.primarySystemIds.length()").value(1))
                .andExpect(jsonPath("$.user.primarySystemIds[0]").value(approval.toString()))
                .andReturn().getResponse().getContentAsString();
        String accessToken = objectMapper.readTree(body).get("accessToken").asString();

        mockMvc.perform(get("/api/v2/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(jsonPath("$.primarySystemIds.length()").value(1))
                .andExpect(jsonPath("$.primarySystemIds[0]").value(approval.toString()));
    }

    @Test
    void 응답_roles에는_계약에_있는_역할만_담는다() throws Exception {
        // DATA_TYPES 신규 절 Role은 BIZ_ADMIN뿐이다. DB에 남은 AGENT_OPS_ADMIN을 내보내면 프론트가 로그인을 멈춘다.
        jdbcTemplate.update("INSERT INTO member_role (member_id, role) VALUES (?, 'BIZ_ADMIN')", MEMBER_ID);
        jdbcTemplate.update("INSERT INTO member_role (member_id, role) VALUES (?, 'AGENT_OPS_ADMIN')", MEMBER_ID);

        String body = mockMvc.perform(login("requester", "hops1234!"))
                .andExpect(jsonPath("$.user.roles.length()").value(1))
                .andExpect(jsonPath("$.user.roles[0]").value("BIZ_ADMIN"))
                .andReturn().getResponse().getContentAsString();
        String accessToken = objectMapper.readTree(body).get("accessToken").asString();

        mockMvc.perform(get("/api/v2/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(jsonPath("$.roles.length()").value(1));
    }

    @Test
    void 담당_시스템이_없어도_primarySystemIds는_빈_배열로_보낸다() throws Exception {
        // 키를 생략하지 않는다. 일반 임직원은 roles=[]·primarySystemIds=[]다 (DATA_TYPES Role).
        mockMvc.perform(login("requester", "hops1234!"))
                .andExpect(jsonPath("$.user.primarySystemIds").isArray())
                .andExpect(jsonPath("$.user.primarySystemIds").isEmpty());
    }

    @Test
    void 받은_토큰으로_내_정보를_조회하면_현재_사용자_정보를_반환한다() throws Exception {
        // 계약: GET /api/v2/auth/me → 200 CurrentUser (WIRE_CONTRACT 2절). 프론트는 이 응답으로 사용자·부서·역할을 확정한다.
        jdbcTemplate.update("INSERT INTO member_role (member_id, role) VALUES (?, 'BIZ_ADMIN')", MEMBER_ID);
        String body = mockMvc.perform(login("requester", "hops1234!"))
                .andReturn().getResponse().getContentAsString();
        String accessToken = objectMapper.readTree(body).get("accessToken").asString();

        mockMvc.perform(get("/api/v2/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(MEMBER_ID.toString()))
                .andExpect(jsonPath("$.employeeNo").value("20260001"))
                .andExpect(jsonPath("$.name").value("테스트 요청자"))
                .andExpect(jsonPath("$.department.code").value("TEST_SALES"))
                .andExpect(jsonPath("$.roles[0]").value("BIZ_ADMIN"));
    }

    @Test
    void 삭제된_회원은_비밀번호가_맞아도_로그인할_수_없다() throws Exception {
        // 회원 삭제는 행을 지우지 않고 deleted_at만 기록한다(ERD). 차단된 계정은 로그인 실패와 같은 401로 응답한다.
        jdbcTemplate.update("UPDATE member SET deleted_at = now() WHERE id = ?", MEMBER_ID);

        mockMvc.perform(login("requester", "hops1234!"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void 로그인_뒤_삭제된_회원은_남은_토큰으로도_내_정보를_조회할_수_없다() throws Exception {
        // PRD 2: 계정 차단 시 기존 인증을 회수한다. 토큰 만료(15분)까지 기다리지 않고 바로 막는다.
        String body = mockMvc.perform(login("requester", "hops1234!"))
                .andReturn().getResponse().getContentAsString();
        String accessToken = objectMapper.readTree(body).get("accessToken").asString();
        jdbcTemplate.update("UPDATE member SET deleted_at = now() WHERE id = ?", MEMBER_ID);

        mockMvc.perform(get("/api/v2/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void 남은_토큰의_회원이_없거나_차단됐으면_회원_ID를_로그로_남긴다(CapturedOutput output) throws Exception {
        String body = mockMvc.perform(login("requester", "hops1234!"))
                .andReturn().getResponse().getContentAsString();
        String accessToken = objectMapper.readTree(body).get("accessToken").asString();
        jdbcTemplate.update("UPDATE member SET deleted_at = now() WHERE id = ?", MEMBER_ID);

        mockMvc.perform(get("/api/v2/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));

        assertThat(output).contains("내 정보 조회 실패: 토큰은 유효하지만 회원이 없거나 차단됨 memberId=" + MEMBER_ID);
        assertThat(output).doesNotContain(accessToken);
    }

    @Test
    void 로그인에_성공하면_회원_ID를_로그로_남기고_토큰은_남기지_않는다(CapturedOutput output) throws Exception {
        String body = mockMvc.perform(login("requester", "hops1234!"))
                .andReturn().getResponse().getContentAsString();
        String accessToken = objectMapper.readTree(body).get("accessToken").asString();

        assertThat(output).contains("로그인 성공 memberId=" + MEMBER_ID);
        assertThat(output).doesNotContain(accessToken);
    }

    @Test
    void 비밀번호가_틀리면_이유와_회원_ID를_남기고_입력한_비밀번호는_남기지_않는다(CapturedOutput output) throws Exception {
        mockMvc.perform(login("requester", "typed-wrong-password"));

        assertThat(output).contains("로그인 실패: 비밀번호 불일치 memberId=" + MEMBER_ID);
        assertThat(output).doesNotContain("typed-wrong-password");
    }

    @Test
    void 없는_로그인ID면_이유만_남기고_입력한_아이디는_남기지_않는다(CapturedOutput output) throws Exception {
        // 사용자가 아이디 칸에 실수로 비밀번호를 넣는 경우가 있어, 찾지 못한 아이디 원문은 로그에 남기지 않는다.
        mockMvc.perform(login("maybe-a-password!", "hops1234!"));

        assertThat(output).contains("로그인 실패: 등록되지 않았거나 차단된 계정");
        assertThat(output).doesNotContain("maybe-a-password!");
    }

    private static MockHttpServletRequestBuilder login(String loginId, String password) {
        return post("/api/v2/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\": \"%s\", \"password\": \"%s\"}".formatted(loginId, password));
    }

    // 허용된 프론트 주소(application.properties hops.cors.allowed-origins). 쿠키를 쓰는 refresh·logout은 이 Origin만 받는다.
    private static final String ALLOWED_ORIGIN = "http://localhost:5173";

    // 브라우저처럼 Refresh 쿠키와 Origin만 붙여 본문 없이 갱신을 요청한다.
    private static MockHttpServletRequestBuilder refresh(String refreshToken) {
        return post("/api/v2/auth/refresh").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .cookie(new Cookie("hops_refresh", refreshToken));
    }

    private static MockHttpServletRequestBuilder logout(String refreshToken) {
        return post("/api/v2/auth/logout").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .cookie(new Cookie("hops_refresh", refreshToken));
    }

    private static String refreshValue(MockHttpServletResponse response) {
        String cookie = refreshCookie(response);
        return cookie.substring("hops_refresh=".length(), cookie.indexOf(';'));
    }

    // Set-Cookie 헤더 중 Refresh 쿠키 한 줄을 찾는다. 예: "hops_refresh=값; Path=/api/v2/auth; Max-Age=604800; HttpOnly; SameSite=Lax"
    private static String refreshCookie(MockHttpServletResponse response) {
        return response.getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith("hops_refresh="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("hops_refresh 쿠키가 없다"));
    }

    @Test
    void 비밀번호가_틀리면_401과_공통_오류_형식으로_응답한다() throws Exception {
        // 프론트 계약(specs/contracts): 로그인 실패는 401 UNAUTHENTICATED. 아이디가 없는지 비밀번호가 틀렸는지는 알려주지 않는다.
        mockMvc.perform(post("/api/v2/auth/login")
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
        mockMvc.perform(post("/api/v2/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId": "", "password": "hops1234!"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }
}
