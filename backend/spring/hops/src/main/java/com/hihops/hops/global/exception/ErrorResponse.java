package com.hihops.hops.global.exception;

import java.util.List;

/**
 * 모든 API가 오류 때 돌려주는 응답 본문 (DATA_TYPES 신규 절 ApiError).
 *
 * <pre>
 * { "code": "INVALID_INPUT", "message": "...", "requestId": "UUID", "retryable": false,
 *   "fieldErrors": [ { "field": "loginId", "code": "NotBlank" } ] }
 * </pre>
 *
 * <p>직접 만들지 않는다. 서비스에서 {@link BusinessException}을 던지면 {@link GlobalExceptionHandler}가 만든다.
 *
 * @param code        오류 종류. {@link ErrorCode} 이름 (프론트가 분기할 때 사용)
 * @param message     사용자에게 보여줄 수 있는 안내 문구. 내부 예외 내용은 넣지 않는다
 * @param requestId   이 요청의 추적 번호. 로그 검색에 사용
 * @param retryable   같은 요청을 다시 보내면 성공할 수 있는지 (일시 장애만 true)
 * @param fieldErrors 입력 칸별 오류. 입력 검증 실패에만 채우고 그 밖에는 빈 배열이다(계약상 키를 생략하지 않는다)
 */
public record ErrorResponse(String code, String message, String requestId, boolean retryable,
        List<FieldErrorResponse> fieldErrors) {

    public static ErrorResponse of(ErrorCode errorCode, String requestId) {
        return of(errorCode, errorCode.message(), requestId);
    }

    public static ErrorResponse of(ErrorCode errorCode, String message, String requestId) {
        return new ErrorResponse(errorCode.name(), message, requestId, errorCode.retryable(), List.of());
    }

    /** 입력 검증 실패(400 INVALID_INPUT)처럼 어떤 칸이 틀렸는지 알려줄 때 쓴다. */
    public static ErrorResponse withFieldErrors(ErrorCode errorCode, String requestId,
            List<FieldErrorResponse> fieldErrors) {
        return new ErrorResponse(errorCode.name(), errorCode.message(), requestId, errorCode.retryable(),
                List.copyOf(fieldErrors));
    }

    /**
     * 입력 칸 하나의 오류 (DATA_TYPES FieldError).
     *
     * @param field 요청 JSON의 항목 이름 (예: loginId)
     * @param code  어긋난 검증 규칙 이름 (예: NotBlank, Size). 입력한 값은 담지 않는다(비밀번호일 수 있다)
     */
    public record FieldErrorResponse(String field, String code) {
    }
}
