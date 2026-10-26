package com.hihops.hops.user.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.hihops.hops.user.domain.Member;

/**
 * member 테이블 조회. 메서드 이름(findByLoginId)만 적으면 Spring Data JPA가
 * "SELECT ... FROM member WHERE login_id = ?" 쿼리를 자동으로 만든다.
 */
public interface MemberRepository extends JpaRepository<Member, UUID> {

    // 로그인 응답·토큰에 소속 부서가 들어가므로 부서도 같은 쿼리로 함께 읽는다(JOIN).
    // Member.department는 LAZY라서, 이게 없으면 DB 연결이 닫힌 뒤 부서를 읽다가 오류가 난다(open-in-view=false).
    @EntityGraph(attributePaths = "department")
    Optional<Member> findByLoginId(String loginId);
}
