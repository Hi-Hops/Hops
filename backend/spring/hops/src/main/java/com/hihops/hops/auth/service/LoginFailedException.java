package com.hihops.hops.auth.service;

import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;

/**
 * 로그인 ID 또는 비밀번호가 맞지 않아 로그인할 수 없다. 401 UNAUTHENTICATED로 응답한다.
 *
 * <p>아이디가 없는 경우와 비밀번호가 틀린 경우를 같은 문구로 응답한다.
 * 구분해서 알려주면 "이 아이디는 존재한다"는 정보를 흘려 계정을 추측하기 쉬워지기 때문이다.
 */
public class LoginFailedException extends BusinessException {

    public LoginFailedException() {
        super(ErrorCode.UNAUTHENTICATED, "아이디 또는 비밀번호를 확인해 주세요.");
    }
}
