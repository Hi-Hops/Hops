package com.hihops.hops.global.exception;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.hihops.hops.global.exception.ErrorResponse.FieldErrorResponse;
import com.hihops.hops.global.filter.RequestIdFilter;
import com.hihops.hops.ticket.domain.Ticket;

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

    // DB 연결·일시 조회 장애는 빈 성공이나 인증 실패가 아니다. 접속 정보·SQL을 응답과 로그에 넣지 않는다.
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleVersionConflict(ObjectOptimisticLockingFailureException exception) {
        return toResponse(Ticket.class.getName().equals(exception.getPersistentClassName())
                ? ErrorCode.TICKET_REVISION_CONFLICT : ErrorCode.CONFLICT);
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, TransientDataAccessException.class,
            CannotCreateTransactionException.class})
    public ResponseEntity<ErrorResponse> handleDatabaseUnavailable(Exception exception) {
        log.warn("DB 조회 일시 장애: {}", exception.getClass().getSimpleName());
        return toResponse(ErrorCode.SERVICE_UNAVAILABLE);
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
        List<FieldErrorResponse> fieldErrors = fieldErrors(exception);
        if (!fieldErrors.isEmpty()) {
            // 입력한 값은 비밀번호일 수도 있으므로, 어떤 항목이 틀렸는지(이름)만 남긴다.
            List<String> fields = fieldErrors.stream().map(FieldErrorResponse::field).distinct().toList();
            log.info("입력 오류 {} {} fields={}", statusCode.value(), errorCode.name(), fields);
        } else {
            // Spring 예외 메시지에는 요청 본문 일부가 섞일 수 있어 예외 종류 이름만 남긴다.
            log.info("요청 오류 {} {}: {}", statusCode.value(), errorCode.name(), exception.getClass().getSimpleName());
        }
        return ResponseEntity.status(statusCode)
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.withFieldErrors(errorCode, RequestIdFilter.currentRequestId(), fieldErrors));
    }

    /**
     * 입력 오류 400을 칸별 fieldErrors로 바꾼다 (WIRE_CONTRACT 12.3 "fieldErrors 표시·입력 보존"). 입력 오류가 아니면 빈 목록.
     * 칸 이름과 어긋난 규칙 이름만 담고 입력한 값은 담지 않는다. 같은 칸·규칙은 한 번만, 칸 이름 순으로 보낸다.
     */
    private static List<FieldErrorResponse> fieldErrors(Exception exception) {
        Stream<FieldErrorResponse> errors = switch (exception) {
            // @Valid @RequestBody 검증 실패
            case MethodArgumentNotValidException invalid -> fromBinding(invalid.getBindingResult().getFieldErrors());
            // 컨트롤러 파라미터에 직접 붙인 규칙(@Min 등) 위반
            case HandlerMethodValidationException invalid -> invalid.getParameterValidationResults().stream()
                    .flatMap(GlobalExceptionHandler::fromParameter);
            // ?count=many 처럼 형식이 틀린 값
            case MethodArgumentTypeMismatchException mismatch -> Stream.of(fieldError(mismatch.getName(), "TypeMismatch"));
            // 필수 쿼리 값이 없음
            case MissingServletRequestParameterException missing ->
                    Stream.of(fieldError(missing.getParameterName(), "Required"));
            default -> Stream.empty();
        };
        return errors.distinct()
                .sorted(Comparator.comparing(FieldErrorResponse::field).thenComparing(FieldErrorResponse::code))
                .toList();
    }

    private static Stream<FieldErrorResponse> fromBinding(List<FieldError> errors) {
        return errors.stream().map(error -> fieldError(error.getField(), error.getCode()));
    }

    private static Stream<FieldErrorResponse> fromParameter(ParameterValidationResult result) {
        if (result instanceof ParameterErrors bean) {
            return fromBinding(bean.getFieldErrors()); // 객체 파라미터는 그 안의 칸별로
        }
        String name = result.getMethodParameter().getParameterName();
        return result.getResolvableErrors().stream()
                .map(error -> fieldError(name, simpleCode(error.getCodes())));
    }

    // 검증 오류의 규칙 이름 목록은 "Min.count", "Min.int", "Min"처럼 자세한 것부터 온다. 마지막(규칙 이름만)을 쓴다.
    private static @Nullable String simpleCode(String @Nullable [] codes) {
        return codes == null || codes.length == 0 ? null : codes[codes.length - 1];
    }

    /** 칸 이름이나 규칙 이름이 비면 프론트가 응답 형식 오류로 보므로 기본값으로 채운다. */
    static FieldErrorResponse fieldError(@Nullable String field, @Nullable String code) {
        return new FieldErrorResponse(hasText(field) ? field : "request", hasText(code) ? code : "Invalid");
    }

    private static boolean hasText(@Nullable String value) {
        return value != null && !value.isBlank();
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
