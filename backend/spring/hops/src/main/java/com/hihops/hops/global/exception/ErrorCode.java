package com.hihops.hops.global.exception;

import java.util.Arrays;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * API 공통 오류 코드. 상태 구분은 docs/API.md §1을 따른다.
 * retryable은 같은 요청을 다시 보내면 성공할 수 있는 일시 오류에만 true로 둔다.
 */
public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다.", false),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.", false),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "접근 권한이 없습니다.", false),
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 대상을 찾을 수 없습니다.", false),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다.", false),
    CONFLICT(HttpStatus.CONFLICT, "다른 요청과 충돌했습니다. 최신 내용을 다시 불러와 주세요.", false),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 본문 형식입니다.", false),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.", true),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.", false),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "일시적으로 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.", true);

    private final HttpStatus status;
    private final String message;
    private final boolean retryable;

    ErrorCode(HttpStatus status, String message, boolean retryable) {
        this.status = status;
        this.message = message;
        this.retryable = retryable;
    }

    public HttpStatus status() {
        return status;
    }

    public String message() {
        return message;
    }

    public boolean retryable() {
        return retryable;
    }

    /**
     * Spring 웹 계층 예외의 HTTP 상태를 공통 오류 코드로 바꾼다. 정의되지 않은 상태는 400대면 INVALID_REQUEST, 그 외는 INTERNAL_ERROR.
     */
    public static ErrorCode fromStatus(HttpStatusCode statusCode) {
        return Arrays.stream(values())
                .filter(code -> code.status.value() == statusCode.value())
                .findFirst()
                .orElse(statusCode.is4xxClientError() ? INVALID_REQUEST : INTERNAL_ERROR);
    }
}
