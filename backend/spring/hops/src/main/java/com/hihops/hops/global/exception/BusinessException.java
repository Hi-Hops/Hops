package com.hihops.hops.global.exception;

/**
 * 업무 규칙 위반을 HTTP 오류로 응답하고 싶을 때 서비스 코드에서 던지는 예외.
 * 던지기만 하면 {@link GlobalExceptionHandler}가 상태 코드와 공통 오류 본문으로 바꿔 응답한다.
 *
 * <pre>
 * // 예: 버전이 다르면 409 응답
 * if (ticket.version() != expectedVersion) {
 *     throw new BusinessException(ErrorCode.CONFLICT);
 * }
 * </pre>
 *
 * <p>새 오류가 필요하면 예외 클래스를 새로 만들지 말고 {@link ErrorCode}에 항목을 추가한다.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.message());
    }

    /**
     * 같은 오류 코드라도 상황에 맞는 안내 문구를 보여주고 싶을 때 쓴다.
     * 예: 401 UNAUTHENTICATED의 기본 문구는 "로그인이 필요합니다."지만, 로그인 실패는 "아이디 또는 비밀번호를 확인해 주세요."
     * 문구는 사용자에게 그대로 보이므로 내부 예외 내용·비밀값을 넣지 않는다.
     */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
