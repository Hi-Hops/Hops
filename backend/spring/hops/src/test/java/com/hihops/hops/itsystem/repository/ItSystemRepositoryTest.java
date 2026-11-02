package com.hihops.hops.itsystem.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.hihops.hops.itsystem.domain.ItSystem;
import com.hihops.hops.user.domain.Member;
import com.hihops.hops.user.repository.MemberRepository;

import jakarta.persistence.EntityManager;

/**
 * 시스템(it_system)을 주담당자와 함께 저장하고 다시 읽는지 확인한다 (docs/ERD.md "시스템 it_system").
 *
 * <p>저장 뒤 {@code flush}(DB에 실제로 INSERT)와 {@code clear}(JPA 메모리 비우기)를 해서,
 * 메모리에 남은 객체가 아니라 DB에서 새로 읽은 값을 확인한다.
 */
@SpringBootTest
@ActiveProfiles({"db", "test"})
@Transactional
class ItSystemRepositoryTest {

    private static final UUID DEPARTMENT_ID = UUID.fromString("d0000000-0000-7000-8000-000000000099");
    private static final UUID OWNER_ID = UUID.fromString("a0000000-0000-7000-8000-000000000099");

    @Autowired
    private ItSystemRepository itSystemRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 테스트용_부서와_주담당자를_넣는다() {
        jdbcTemplate.update("INSERT INTO department (id, code, name) VALUES (?, 'TEST_IT', '테스트 IT팀')", DEPARTMENT_ID);
        jdbcTemplate.update("INSERT INTO member (id, login_id, employee_no, password_hash, name, department_id) "
                + "VALUES (?, 'test.owner', '29990099', 'hash', '테스트 주담당자', ?)", OWNER_ID, DEPARTMENT_ID);
    }

    @Test
    void 시스템을_주담당자와_함께_저장하고_DB에서_다시_읽는다() {
        Member owner = memberRepository.findById(OWNER_ID).orElseThrow();
        ItSystem saved = itSystemRepository.save(
                new ItSystem("E_APPROVAL", "전자결재", "결재 상신·승인·문서 조회", owner));
        entityManager.flush();
        entityManager.clear();

        ItSystem found = itSystemRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getCode()).isEqualTo("E_APPROVAL");
        assertThat(found.getName()).isEqualTo("전자결재");
        assertThat(found.getDescription()).isEqualTo("결재 상신·승인·문서 조회");
        assertThat(found.getPrimaryOwner().getId()).isEqualTo(OWNER_ID);
        // 주담당자가 아직 바뀐 적 없어 담당 세대는 1이다
        assertThat(found.getOwnershipVersion()).isEqualTo(1);
    }
}
