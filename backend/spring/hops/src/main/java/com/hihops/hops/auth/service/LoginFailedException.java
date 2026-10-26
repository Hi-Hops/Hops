package com.hihops.hops.auth.service;

/**
 * 로그인 ID 또는 비밀번호가 맞지 않아 로그인할 수 없다.
 */
public class LoginFailedException extends RuntimeException {

    public LoginFailedException() {
        super("아이디 또는 비밀번호가 올바르지 않습니다.");
    }
}
