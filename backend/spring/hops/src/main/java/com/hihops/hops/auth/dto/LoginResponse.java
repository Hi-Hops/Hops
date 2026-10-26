package com.hihops.hops.auth.dto;

import java.util.UUID;

import com.hihops.hops.user.domain.Member;

/**
 * 로그인 성공 응답의 사용자 정보. 필드 이름은 API 설계의 JWT 클레임(employeeNo)과 맞춘다.
 * 비밀번호 해시는 내보내지 않는다.
 */
public record LoginResponse(UUID userId, String employeeNo, String displayName) {

    public static LoginResponse from(Member member) {
        return new LoginResponse(member.getId(), member.getEmployeeNo(), member.getName());
    }
}
