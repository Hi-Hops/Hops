package com.hihops.hops.global.filter;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 모든 API 호출의 처리 전·후를 기록하는 필터.
 *
 * <p>로그 예시 (requestId는 RequestIdFilter가 붙인다):
 * <pre>
 * INFO [requestId=4e95e5b4-...] → GET /api/tickets
 * INFO [requestId=4e95e5b4-...] ← GET /api/tickets 200 (35ms)
 * </pre>
 *
 * <p>남기지 않는 것: 요청·응답 본문(비밀번호), Authorization 헤더(JWT), 쿼리 문자열(민감 값이 섞일 수 있음).
 * 근거: docs/API.md §1 "JWT 원문·비밀번호·서명키는 로그에 남기지 않는다". 이 필터를 고칠 때도 이 규칙을 지킨다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1) // 2번째 필터. requestId가 정해진 뒤, Security보다 먼저 실행해 401·403 요청도 기록한다.
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String method = request.getMethod();
        String path = request.getRequestURI(); // 경로만 쓴다. getRequestURL·getQueryString은 쿼리 값이 포함될 수 있다.
        long startedAt = System.nanoTime();

        log.info("→ {} {}", method, path); // 처리 전
        try {
            chain.doFilter(request, response); // 실제 처리 (다음 필터 → Security → Controller)
        } finally {
            // 처리 중 예외가 나도 반드시 처리 후 로그를 남긴다.
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
            log.info("← {} {} {} ({}ms)", method, path, response.getStatus(), elapsedMs); // 처리 후
        }
    }
}
