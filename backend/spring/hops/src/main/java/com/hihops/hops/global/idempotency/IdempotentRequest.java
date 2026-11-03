package com.hihops.hops.global.idempotency;

import java.util.Locale;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.jwt.Jwt;

import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;

/**
 * {@link IdempotencyService}에 넘기는 변경 요청 한 건. 같은 요청인지 판단하는 재료(요청자·키·메서드·경로·본문)를 묶는다.
 *
 * <p>Controller에서 {@link #of}로 만든다.
 * <pre>
 * &#64;PostMapping
 * public ResponseEntity&lt;CommandReceipt&gt; create(&#64;AuthenticationPrincipal Jwt jwt, HttpServletRequest http,
 *         &#64;RequestHeader(value = "Idempotency-Key", required = false) String key,
 *         &#64;Valid &#64;RequestBody InquiryCreateCommand command) {
 *     IdempotentRequest request = IdempotentRequest.of(jwt, http, key, command.requestId(), command);
 *     return idempotencyService.execute(request, operationId -&gt; ...).toResponseEntity();
 * }
 * </pre>
 *
 * @param actorId     요청자 회원 ID (JWT sub). 다른 사람의 같은 키와 섞이지 않게 요청자별로 나눈다
 * @param requestId   본문의 requestId. Idempotency-Key 헤더와 같은 값이다
 * @param httpMethod  HTTP 메서드 (예: POST)
 * @param requestPath 정규화한 경로. 소문자로 바꾸고 끝의 / 를 뗀다 (UUID 대소문자 차이로 다른 요청이 되지 않게)
 * @param body        요청 본문 객체. 같은 내용인지 비교할 해시를 만드는 데 쓴다
 */
public record IdempotentRequest(UUID actorId, UUID requestId, String httpMethod, String requestPath, Object body) {

    /** 프론트가 변경 요청마다 보내는 헤더 이름 (WIRE_CONTRACT 12.1) */
    public static final String HEADER = "Idempotency-Key";

    /**
     * 헤더의 Idempotency-Key와 본문의 requestId가 같은지 확인하고 요청을 만든다.
     * 헤더가 없거나 UUID가 아니거나 본문 값과 다르면 400 INVALID_INPUT이다.
     */
    public static IdempotentRequest of(Jwt jwt, HttpServletRequest http, @Nullable String idempotencyKey,
            @Nullable UUID requestId, Object body) {
        if (requestId == null || !requestId.equals(parseKey(idempotencyKey))) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "요청 키(Idempotency-Key)가 본문의 requestId와 같아야 합니다.");
        }
        UUID actorId = UUID.fromString(jwt.getSubject()); // sub = 회원 UUID (AccessTokenIssuer)
        return new IdempotentRequest(actorId, requestId, http.getMethod(), normalizePath(http.getRequestURI()), body);
    }

    private static @Nullable UUID parseKey(@Nullable String idempotencyKey) {
        if (idempotencyKey == null) {
            return null;
        }
        try {
            return UUID.fromString(idempotencyKey.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String normalizePath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.length() > 1 && lower.endsWith("/") ? lower.substring(0, lower.length() - 1) : lower;
    }
}
