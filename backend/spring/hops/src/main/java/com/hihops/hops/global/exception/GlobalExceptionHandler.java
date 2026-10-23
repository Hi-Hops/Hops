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
 * 어디서 예외가 나든 받아서 공통 오류 응답({@link ErrorResponse})으로 바꾸는 곳.
 *
 * <p>처리 경로는 세 가지다.
 * <ol>
 *   <li>{@link BusinessException}: 서비스가 일부러 던진 업무 오류 → ErrorCode의 상태로 응답</li>
 *   <li>Spring 웹 표준 예외(입력 검증 실패, JSON 형식 오류, 없는 경로, 메서드 불일치 등)
 *       → 상위 클래스 {@link ResponseEntityExceptionHandler}가 상태를 정하고, {@link #handleExceptionInternal}에서 본문만 바꾼다</li>
 *   <li>그 밖의 예상하지 못한 예외 → 500. 원인은 서버 로그에만 남기고 응답에는 담지 않는다 (docs/API.md §1)</li>
 * </ol>
 *
 * <p>Controller마다 try-catch를 쓰지 않고, 예외를 던지기만 하면 된다.
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
        log.error("처리되지 않은 예외", exception); // 스택 트레이스는 로그에만 남긴다.
        return toResponse(ErrorCode.INTERNAL_ERROR);
    }

    // Spring 웹 표준 예외의 응답 본문을 공통 형식으로 바꾼다. 상태 코드와 헤더(예: 405의 Allow)는 Spring이 정한 값을 유지한다.
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
