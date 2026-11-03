package com.hihops.hops.department.dto;

import java.util.UUID;

import com.hihops.hops.department.domain.Department;

/**
 * 부서 정보 응답 (specs/contracts/DATA_TYPES.md 신규 절 Department: id·code·name).
 *
 * <p>isRoutable(IT 처리 부서 여부)은 신규 응답에서 뺐다. 담당 메뉴는 부서가 아니라 CurrentUser.primarySystemIds로 정한다.
 * DB의 is_routable은 서버 권한 검사(예: 부서 SSE)에만 쓴다.
 */
public record DepartmentResponse(UUID id, String code, String name) {

    public static DepartmentResponse from(Department department) {
        return new DepartmentResponse(department.getId(), department.getCode(), department.getName());
    }
}
