package com.hihops.hops.global.exception;

/**
 * 업무 규칙 위반을 공통 오류 코드로 응답할 때 던진다.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
