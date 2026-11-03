package com.hihops.hops.user.dto;

import java.util.UUID;

import com.hihops.hops.department.dto.DepartmentResponse;
import com.hihops.hops.user.domain.Member;

/** 티켓에서 표시할 최소 사용자 정보. 로그인 ID·사번·역할은 포함하지 않는다. */
public record UserSummaryResponse(UUID id, String name, DepartmentResponse department) {
    public static UserSummaryResponse from(Member member) {
        return new UserSummaryResponse(member.getId(), member.getName(), DepartmentResponse.from(member.getDepartment()));
    }
}
