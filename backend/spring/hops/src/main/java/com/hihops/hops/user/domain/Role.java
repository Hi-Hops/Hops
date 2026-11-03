package com.hihops.hops.user.domain;

import java.util.Collection;
import java.util.List;

/**
 * 부서와 별개로 부여하는 역할 (specs/contracts/DATA_TYPES.md Role, docs/PRD.md 2).
 *
 * <p>DB(member_role.role)와 JWT roles 클레임에는 이 이름 그대로 저장된다.
 * Spring Security에서는 ROLE_ 접두사가 붙어 {@code hasRole('BIZ_ADMIN')}처럼 검사한다 (JwtConfig).
 */
public enum Role {
    /** 업무 관리자: 관리자 대시보드, 예외 티켓·수동 배정 */
    BIZ_ADMIN(true),
    /**
     * 에이전트 운영 관리자: AI 실행 이력·기술 복구. 업무 결론은 바꾸지 않는다.
     * 신규 계약(PRD 1.44)에서 빠진 역할이라 API 응답에는 내보내지 않는다. DB 값 정리는 별도 작업이다.
     */
    AGENT_OPS_ADMIN(false);

    private final boolean inContract;

    Role(boolean inContract) {
        this.inContract = inContract;
    }

    /** 신규 계약(DATA_TYPES 신규 절 Role)에 있는 역할인지. 응답의 roles에는 이 역할만 담는다. */
    public boolean inContract() {
        return inContract;
    }

    /** 계약에 있는 역할만 남긴다. 응답 roles·JWT roles 클레임·SSE 문맥 비교가 모두 이 기준을 쓴다. */
    public static List<Role> contractRoles(Collection<Role> roles) {
        return roles.stream().filter(Role::inContract).toList();
    }
}
