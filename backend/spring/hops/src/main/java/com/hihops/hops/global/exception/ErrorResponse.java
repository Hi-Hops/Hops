package com.hihops.hops.global.exception;

/**
 * 모든 API가 오류 때 돌려주는 응답 본문. 형식은 docs/API.md §1의 오류 외피를 따른다.
 *
 * <pre>
 * { "code": "CONFLICT", "message": "...", "requestId": "UUID", "retryable": false }
 * </pre>
 *
 * <p>직접 만들지 않는다. 서비스에서 {@link BusinessException}을 던지면 {@link GlobalExceptionHandler}가 만든다.
 *
 * @param code      오류 종류. {@link ErrorCode} 이름 (프론트가 분기할 때 사용)
 * @param message   사용자에게 보여줄 수 있는 안내 문구. 내부 예외 내용은 넣지 않는다
 * @param requestId 이 요청의 추적 번호. 로그 검색에 사용
 * @param retryable 같은 요청을 다시 보내면 성공할 수 있는지 (일시 장애만 true)
 */
public record ErrorResponse(String code, String message, String requestId, boolean retryable) {

    public static ErrorResponse of(ErrorCode errorCode, String requestId) {
        return of(errorCode, errorCode.message(), requestId);
    }

    public static ErrorResponse of(ErrorCode errorCode, String message, String requestId) {
        return new ErrorResponse(errorCode.name(), message, requestId, errorCode.retryable());
    }
}
