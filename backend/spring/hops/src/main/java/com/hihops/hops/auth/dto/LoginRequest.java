package com.hihops.hops.auth.dto;

/**
 * 로그인 요청. loginId는 사번과 별도인 로그인 ID다.
 */
public record LoginRequest(String loginId, String password) {

    @Override
    public String toString() {
        return "LoginRequest[loginId=" + loginId + "]";
    }
}
