package com.hihops.hops.user.domain;

/**
 * 부서와 별개로 부여하는 역할 (specs/contracts/DATA_TYPES.md Role, docs/PRD.md 2).
 *
 * <p>DB(member_role.role)와 JWT roles 클레임에는 이 이름 그대로 저장된다.
 * Spring Security에서는 ROLE_ 접두사가 붙어 {@code hasRole('BIZ_ADMIN')}처럼 검사한다 (JwtConfig).
 */
public enum Role {
    /** 업무 관리자: 관리자 대시보드, 예외 티켓·수동 배정 */
    BIZ_ADMIN,
    /** 에이전트 운영 관리자: AI 실행 이력·기술 복구. 업무 결론은 바꾸지 않는다 */
    AGENT_OPS_ADMIN
}
