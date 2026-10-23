package com.hihops.hops.global.exception;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.hihops.hops.global.filter.RequestIdFilter;

/**
 * 모든 예외를 공통 오류 응답으로 바꾼다.
 * Spring 웹 계층의 표준 예외(입력 검증·JSON 형식·없는 경로·메서드 불일치 등)는 상위 클래스가 상태를 정하고 여기서 본문만 바꾼다.
 * 내부 예외 메시지는 응답에 담지 않는다 (docs/API.md §1).
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException exception) {
        return toResponse(exception.errorCode());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception exception) {
        log.error("처리되지 않은 예외", exception);
        return toResponse(ErrorCode.INTERNAL_ERROR);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, @Nullable Object body,
            HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        return ResponseEntity.status(statusCode)
                .headers(headers)
                .body(errorBody(ErrorCode.fromStatus(statusCode)));
    }

    private static ResponseEntity<ErrorResponse> toResponse(ErrorCode errorCode) {
        return ResponseEntity.status(errorCode.status()).body(errorBody(errorCode));
    }

    private static ErrorResponse errorBody(ErrorCode errorCode) {
        return ErrorResponse.of(errorCode, RequestIdFilter.currentRequestId());
    }
}
