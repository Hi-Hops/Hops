package com.hihops.hops.user.dto;

import java.util.UUID;

import com.hihops.hops.user.domain.Member;

/**
 * 로그인한 사용자 정보. 계약 이름은 CurrentUser다 (specs/contracts/DATA_TYPES.md).
 * 비밀번호 해시·로그인 ID는 내보내지 않는다.
 *
 * <p>계약의 department·roles는 다음 작업에서 추가한다.
 */
public record CurrentUserResponse(UUID id, String employeeNo, String name) {

    public static CurrentUserResponse from(Member member) {
        return new CurrentUserResponse(member.getId(), member.getEmployeeNo(), member.getName());
    }
}
