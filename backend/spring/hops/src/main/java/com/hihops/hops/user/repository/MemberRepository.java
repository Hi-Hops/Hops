package com.hihops.hops.user.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hihops.hops.user.domain.Member;

/**
 * member 테이블 조회. 메서드 이름(findByLoginId)만 적으면 Spring Data JPA가
 * "SELECT ... FROM member WHERE login_id = ?" 쿼리를 자동으로 만든다.
 */
public interface MemberRepository extends JpaRepository<Member, UUID> {

    Optional<Member> findByLoginId(String loginId);
}
