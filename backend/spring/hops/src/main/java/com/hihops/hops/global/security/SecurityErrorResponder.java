package com.hihops.hops.global.security;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.global.exception.ErrorResponse;
import com.hihops.hops.global.filter.RequestIdFilter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Security가 요청을 막을 때(401·403)도 공통 오류 형식({@link ErrorResponse})으로 응답하게 한다.
 *
 * <p>왜 따로 있나: Security는 Controller보다 앞(필터)에서 요청을 막기 때문에
 * {@link com.hihops.hops.global.exception.GlobalExceptionHandler}가 닿지 않는다.
 *
 * <ul>
 *   <li>401 {@code UNAUTHENTICATED}: 로그인(유효한 토큰) 없이 보호된 API를 호출함</li>
 *   <li>401 {@code ACCESS_TOKEN_EXPIRED}: 서명은 맞지만 만료된 토큰. 프론트는 refresh 1회 후 다시 보낸다 (WIRE_CONTRACT 12.3)</li>
 *   <li>403 {@code ACCESS_DENIED}: 로그인은 했지만 그 API를 쓸 권한이 없음</li>
 *   <li>403 {@code ORIGIN_DENIED}: 허용하지 않은 주소에서 Refresh 쿠키 요청을 보냄 ({@link CookieOriginFilter})</li>
 * </ul>
 */
@Component
public class SecurityErrorResponder implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final JsonMapper jsonMapper;

    public SecurityErrorResponder(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    // 401: 인증이 필요한데 없거나 잘못된 경우
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        write(response, JwtConfig.isOnlyExpired(exception) ? ErrorCode.ACCESS_TOKEN_EXPIRED : ErrorCode.UNAUTHENTICATED);
    }

    // 403: 인증은 됐지만 권한이 없는 경우
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException exception) throws IOException {
        write(response, ErrorCode.ACCESS_DENIED);
    }

    // 필터에서 요청을 막을 때 공통 오류 형식으로 쓴다 (CookieOriginFilter도 사용)
    void write(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        jsonMapper.writeValue(response.getOutputStream(),
                ErrorResponse.of(errorCode, RequestIdFilter.currentRequestId()));
    }
}
