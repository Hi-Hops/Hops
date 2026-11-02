package com.hihops.hops.global.exception;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
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
        ErrorCode errorCode = exception.errorCode();
        // 예상한 업무 오류라 스택 트레이스 없이 한 줄만 남긴다. 문구는 사용자에게 보여주는 것과 같아 비밀값이 없다.
        log.info("업무 오류 {} {}: {}", errorCode.status().value(), errorCode.name(), exception.getMessage());
        // SSE 연결 전 오류도 JSON 계약을 따른다. Accept가 text/event-stream이어도 오류 직렬화가 실패하지 않게 한다.
        return ResponseEntity.status(errorCode.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of(errorCode, exception.getMessage(), RequestIdFilter.currentRequestId()));
    }

    // Controller·Service 안에서 난 권한 예외. 아래 Exception 처리기에 걸려 500이 되지 않도록 먼저 잡는다.
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException exception) {
        return toResponse(ErrorCode.ACCESS_DENIED);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException exception) {
        return toResponse(ErrorCode.UNAUTHENTICATED);
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
        ErrorCode errorCode = ErrorCode.fromStatus(statusCode);
        if (exception instanceof MethodArgumentNotValidException invalid) {
            // 입력한 값은 비밀번호일 수도 있으므로, 어떤 항목이 틀렸는지(이름)만 남긴다.
            List<String> fields = invalid.getBindingResult().getFieldErrors().stream()
                    .map(FieldError::getField).distinct().sorted().toList();
            log.info("입력 오류 {} {} fields={}", statusCode.value(), errorCode.name(), fields);
        } else {
            // Spring 예외 메시지에는 요청 본문 일부가 섞일 수 있어 예외 종류 이름만 남긴다.
            log.info("요청 오류 {} {}: {}", statusCode.value(), errorCode.name(), exception.getClass().getSimpleName());
        }
        return ResponseEntity.status(statusCode)
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .body(errorBody(errorCode));
    }

    private static ResponseEntity<ErrorResponse> toResponse(ErrorCode errorCode) {
        return ResponseEntity.status(errorCode.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(errorBody(errorCode));
    }

    private static ErrorResponse errorBody(ErrorCode errorCode) {
        return ErrorResponse.of(errorCode, RequestIdFilter.currentRequestId());
    }
}
