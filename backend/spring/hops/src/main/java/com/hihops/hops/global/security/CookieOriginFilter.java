package com.hihops.hops.global.security;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

import com.hihops.hops.global.config.ApiPathConfig;
import com.hihops.hops.global.exception.ErrorCode;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Refresh 쿠키를 쓰는 요청(refresh·logout)은 허용한 프론트 주소에서 온 것만 받는다 (WIRE_CONTRACT 12.1).
 *
 * <p>왜 필요한가: 브라우저는 쿠키를 자동으로 붙인다. 다른 사이트가 몰래 이 주소로 POST를 보내면
 * 사용자 모르게 로그아웃되거나 갱신이 일어날 수 있다(CSRF). 브라우저가 붙이는 {@code Origin} 헤더로 출처를 확인한다.
 * Origin이 없으면 출처를 알 수 없으므로 막는다.
 *
 * <p>Spring CORS 필터보다 앞에 둔다. CORS 필터도 틀린 Origin을 403으로 막지만 본문이 공통 오류 형식이 아니다.
 * Bean으로 등록하지 않는다: Bean이면 Spring Boot가 일반 서블릿 필터로도 한 번 더 등록한다(SecurityConfig에서 직접 만든다).
 */
class CookieOriginFilter extends OncePerRequestFilter {

    private static final Set<String> COOKIE_PATHS = Set.of(
            ApiPathConfig.PREFIX + "/auth/refresh", ApiPathConfig.PREFIX + "/auth/logout");

    private final List<String> allowedOrigins;
    private final SecurityErrorResponder errorResponder;

    CookieOriginFilter(List<String> allowedOrigins, SecurityErrorResponder errorResponder) {
        this.allowedOrigins = List.copyOf(allowedOrigins);
        this.errorResponder = errorResponder;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(HttpMethod.POST.matches(request.getMethod()) && COOKIE_PATHS.contains(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin == null || !allowedOrigins.contains(origin)) {
            errorResponder.write(response, ErrorCode.ORIGIN_DENIED);
            return;
        }
        chain.doFilter(request, response);
    }
}
