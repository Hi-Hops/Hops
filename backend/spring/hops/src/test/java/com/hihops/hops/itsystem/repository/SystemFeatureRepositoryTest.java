package com.hihops.hops.itsystem.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.hihops.hops.itsystem.domain.ItSystem;
import com.hihops.hops.itsystem.domain.SystemFeature;

import jakarta.persistence.EntityManager;

/**
 * 시스템 기능(system_feature)을 저장하고, 같은 시스템에 같은 기능 코드가 두 번 들어가지 않는지 확인한다
 * (docs/ERD.md "시스템 기능 system_feature", 중복 불가 조합: 시스템 ID + 기능 코드).
 */
@SpringBootTest
@ActiveProfiles({"db", "test"})
@Transactional
class SystemFeatureRepositoryTest {

    private static final UUID DEPARTMENT_ID = UUID.fromString("d0000000-0000-7000-8000-000000000099");
    private static final UUID OWNER_ID = UUID.fromString("a0000000-0000-7000-8000-000000000099");
    private static final UUID SYSTEM_ID = UUID.fromString("c0000000-0000-7000-8000-000000000099");

    @Autowired
    private SystemFeatureRepository systemFeatureRepository;

    @Autowired
    private ItSystemRepository itSystemRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 테스트용_부서와_주담당자와_시스템을_넣는다() {
        jdbcTemplate.update("INSERT INTO department (id, code, name) VALUES (?, 'TEST_IT', '테스트 IT팀')", DEPARTMENT_ID);
        jdbcTemplate.update("INSERT INTO member (id, login_id, employee_no, password_hash, name, department_id) "
                + "VALUES (?, 'test.owner', '29990099', 'hash', '테스트 주담당자', ?)", OWNER_ID, DEPARTMENT_ID);
        jdbcTemplate.update("INSERT INTO it_system (id, code, name, primary_owner_member_id) "
                + "VALUES (?, 'TEST_APPROVAL', '테스트 전자결재', ?)", SYSTEM_ID, OWNER_ID);
    }

    @Test
    void 기능을_저장하고_같은_시스템에_같은_기능_코드는_막는다() {
        ItSystem system = itSystemRepository.findById(SYSTEM_ID).orElseThrow();
        SystemFeature saved = systemFeatureRepository.save(
                new SystemFeature(system, "DOCUMENT_VIEW", "문서 조회", "결재 문서 목록·상세 조회"));
        entityManager.flush();
        entityManager.clear();

        SystemFeature found = systemFeatureRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getItSystem().getId()).isEqualTo(SYSTEM_ID);
        assertThat(found.getCode()).isEqualTo("DOCUMENT_VIEW");
        assertThat(found.getName()).isEqualTo("문서 조회");
        assertThat(found.getDescription()).isEqualTo("결재 문서 목록·상세 조회");

        // 같은 시스템 + 같은 기능 코드 → DB의 중복 불가 제약이 막는다
        ItSystem sameSystem = itSystemRepository.findById(SYSTEM_ID).orElseThrow();
        assertThatThrownBy(() -> systemFeatureRepository.saveAndFlush(
                new SystemFeature(sameSystem, "DOCUMENT_VIEW", "문서 조회 중복", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
