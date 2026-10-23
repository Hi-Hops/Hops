package com.hihops.hops.global.exception;

/**
 * API 공통 오류 응답. 형식은 docs/API.md §1의 오류 외피를 따른다.
 */
public record ErrorResponse(String code, String message, String requestId, boolean retryable) {

    public static ErrorResponse of(ErrorCode errorCode, String requestId) {
        return new ErrorResponse(errorCode.name(), errorCode.message(), requestId, errorCode.retryable());
    }
}
