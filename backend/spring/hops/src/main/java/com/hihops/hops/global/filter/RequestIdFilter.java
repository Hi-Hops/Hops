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
 * 요청마다 고유 번호(requestId, UUID)를 붙이는 필터. 모든 필터 중 가장 먼저 실행된다.
 *
 * <p>왜 필요한가: docs/API.md §1의 오류 응답에 requestId가 들어간다. 사용자가 오류 화면의 requestId를 알려주면
 * 서버 로그에서 같은 값으로 그 요청의 기록을 모두 찾을 수 있다.
 *
 * <p>requestId가 들어가는 곳:
 * <ul>
 *   <li>응답 헤더 {@code X-Request-Id}</li>
 *   <li>MDC(요청별 메모장) → 모든 로그 줄의 {@code [requestId=...]} (application.properties의 logging.pattern.level)</li>
 *   <li>오류 응답 본문의 requestId ({@link #currentRequestId()}로 꺼내 씀)</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE) // 1번째 필터. 이후 필터와 로그가 모두 requestId를 쓸 수 있게 가장 먼저 실행한다.
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
            chain.doFilter(request, response); // 다음 필터 → Controller로 넘긴다.
        } finally {
            // 서버는 스레드를 재사용하므로, 지우지 않으면 다음 요청 로그에 이전 requestId가 섞인다.
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * 지금 처리 중인 요청의 requestId를 돌려준다. 예외 처리기 등 필터 밖의 코드에서 사용한다.
     * 필터를 거치지 않은 호출(테스트 등)에서는 새 값을 만들어 null이 나가지 않게 한다.
     */
    public static String currentRequestId() {
        String requestId = MDC.get(MDC_KEY);
        return requestId != null ? requestId : UUID.randomUUID().toString();
    }

    /**
     * 클라이언트가 보낸 X-Request-Id가 UUID 형식이면 그대로 이어 쓰고, 없거나 형식이 다르면 새로 만든다.
     * 아무 문자열이나 받으면 줄바꿈 등을 넣어 가짜 로그 줄을 만드는 공격(로그 위조)이 가능하기 때문이다.
     */
    private static String reuseOrCreate(String headerValue) {
        if (headerValue != null) {
            try {
                return UUID.fromString(headerValue).toString();
            } catch (IllegalArgumentException ignored) {
                // UUID가 아니면 버리고 아래에서 새로 만든다.
            }
        }
        return UUID.randomUUID().toString();
    }
}
