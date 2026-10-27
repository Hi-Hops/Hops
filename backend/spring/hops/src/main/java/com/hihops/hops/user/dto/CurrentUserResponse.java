package com.hihops.hops.user.dto;

import java.util.List;
import java.util.UUID;

import com.hihops.hops.department.dto.DepartmentResponse;
import com.hihops.hops.user.domain.Member;
import com.hihops.hops.user.domain.Role;

/**
 * 로그인한 사용자 정보. 계약 이름은 CurrentUser다 (specs/contracts/DATA_TYPES.md).
 * 비밀번호 해시·로그인 ID는 내보내지 않는다.
 *
 * <p>department는 사용자 소속 부서다. 티켓을 처리하는 부서(배정 대상)와는 별개다.
 * roles는 부서와 별개인 관리자 역할이며, 없으면 빈 배열이다(계약상 키를 생략하지 않는다).
 */
public record CurrentUserResponse(UUID id, String employeeNo, String name, DepartmentResponse department,
        List<Role> roles) {

    public static CurrentUserResponse from(Member member) {
        return new CurrentUserResponse(member.getId(), member.getEmployeeNo(), member.getName(),
                DepartmentResponse.from(member.getDepartment()), member.getRoles());
    }
}
