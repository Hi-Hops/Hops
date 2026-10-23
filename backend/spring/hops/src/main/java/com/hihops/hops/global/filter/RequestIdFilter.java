package com.hihops.hops.global.filter;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 요청마다 requestId(UUID)를 정해 MDC와 응답 헤더에 넣는다. 다른 필터보다 먼저 실행된다.
 * 클라이언트가 보낸 X-Request-Id가 UUID 형식이면 이어서 쓰고, 아니면 새로 만든다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = reuseOrCreate(request.getHeader(HEADER));
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * 현재 요청의 requestId. 필터 밖에서 호출되면 새 값을 만든다.
     */
    public static String currentRequestId() {
        String requestId = MDC.get(MDC_KEY);
        return requestId != null ? requestId : UUID.randomUUID().toString();
    }

    private static String reuseOrCreate(String headerValue) {
        if (headerValue != null) {
            try {
                return UUID.fromString(headerValue).toString();
            } catch (IllegalArgumentException ignored) {
                // 형식이 맞지 않는 값은 로그 위조를 막기 위해 버리고 새로 만든다.
            }
        }
        return UUID.randomUUID().toString();
    }
}
