package com.hihops.hops.auth.dto;

import jakarta.validation.constraints.NotEmpty;

/**
 * 로그인 요청. loginId는 사번과 별도인 로그인 ID다.
 *
 * <p>계약(specs/contracts/DATA_TYPES.md LoginRequest): 두 값 모두 최소 1글자. 비어 있으면 400 INVALID_INPUT.
 * {@code @NotBlank}가 아닌 {@code @NotEmpty}를 쓰는 이유: 계약은 "1글자 이상"만 요구하고,
 * 비밀번호에는 공백이 들어갈 수 있어서 공백만 있는 값까지 막지 않는다.
 */
public record LoginRequest(@NotEmpty String loginId, @NotEmpty String password) {

    // 로그에 요청 객체가 찍혀도 비밀번호가 남지 않도록 loginId만 보여준다.
    @Override
    public String toString() {
        return "LoginRequest[loginId=" + loginId + "]";
    }
}
