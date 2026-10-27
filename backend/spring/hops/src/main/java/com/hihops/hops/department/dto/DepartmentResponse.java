package com.hihops.hops.department.dto;

import java.util.UUID;

import com.hihops.hops.department.domain.Department;

/**
 * 부서 정보 응답 (specs/contracts/DATA_TYPES.md Department).
 *
 * <p>isRoutable이 true면 IT 처리 부서다. 프론트는 이 값으로 "부서 티켓 현황" 메뉴를 보여준다
 * (최종 권한은 서버가 다시 검사한다).
 */
public record DepartmentResponse(UUID id, String code, String name, boolean isRoutable) {

    public static DepartmentResponse from(Department department) {
        return new DepartmentResponse(department.getId(), department.getCode(), department.getName(),
                department.isRoutable());
    }
}
