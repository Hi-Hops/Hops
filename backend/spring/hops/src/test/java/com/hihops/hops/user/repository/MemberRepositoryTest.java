package com.hihops.hops.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.hihops.hops.user.domain.Member;

/**
 * 회원을 로컬 PostgreSQL 테스트 DB(hops_test)의 member 테이블에서 찾는지 확인한다.
 *
 * <p>테스트 DB에는 가상 데이터(seed)가 없어서, 테스트에 필요한 부서·회원을 직접 넣는다.
 * {@code @Transactional}이라 테스트가 끝나면 넣은 행은 되돌려진다(rollback).
 */
@SpringBootTest
@ActiveProfiles({"db", "test"})
@Transactional
class MemberRepositoryTest {

    private static final UUID DEPARTMENT_ID = UUID.fromString("d0000000-0000-7000-8000-000000000099");

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 테스트용_부서와_회원을_넣는다() {
        jdbcTemplate.update("INSERT INTO department (id, code, name, is_routable) VALUES (?, 'TEST_NETWORK', '테스트 네트워크', true)",
                DEPARTMENT_ID);
        jdbcTemplate.update("INSERT INTO member (login_id, employee_no, password_hash, name, department_id) "
                + "VALUES ('test.network', '29990001', 'hash', '테스트 담당자', ?)", DEPARTMENT_ID);
    }

    @Test
    void 로그인ID로_DB의_회원과_소속_부서를_찾는다() {
        Member member = memberRepository.findByLoginId("test.network").orElseThrow();

        assertThat(member.getId()).isNotNull();
        assertThat(member.getEmployeeNo()).isEqualTo("29990001");
        assertThat(member.getName()).isEqualTo("테스트 담당자");
        assertThat(member.getDepartment().getCode()).isEqualTo("TEST_NETWORK");
    }
}
