package com.hihops.hops.user.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.hihops.hops.user.domain.Member;

/**
 * member 테이블 조회. 메서드 이름만 적으면 Spring Data JPA가 쿼리를 자동으로 만든다.
 * 예: findByLoginIdAndDeletedAtIsNull → "SELECT ... FROM member WHERE login_id = ? AND deleted_at IS NULL"
 *
 * <p>삭제(차단)된 회원은 로그인·내 정보 조회에서 찾지 못하게 deleted_at IS NULL 조건을 붙인다.
 */
public interface MemberRepository extends JpaRepository<Member, UUID> {

    // 로그인 응답·토큰에 소속 부서와 역할이 들어가므로 같은 쿼리로 함께 읽는다(JOIN).
    // 둘 다 LAZY라서, 이게 없으면 DB 연결이 닫힌 뒤 읽다가 오류가 난다(open-in-view=false).
    @EntityGraph(attributePaths = {"department", "memberRoles"})
    Optional<Member> findByLoginIdAndDeletedAtIsNull(String loginId);

    // 토큰의 sub(회원 UUID)로 현재 정보를 다시 읽을 때 쓴다 (GET /api/auth/me). 메서드 이름의 "WithDepartmentAndRoles"는 설명용이다.
    @EntityGraph(attributePaths = {"department", "memberRoles"})
    Optional<Member> findWithDepartmentAndRolesByIdAndDeletedAtIsNull(UUID id);
}
