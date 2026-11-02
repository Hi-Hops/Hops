package com.hihops.hops.global.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.hihops.hops.user.domain.Member;
import com.hihops.hops.user.repository.MemberRepository;

import jakarta.persistence.EntityManager;

/**
 * 중복 요청 방지 기록(idempotency_key)을 접수 상태로 저장하고, 같은 사람이 같은 계약에서 같은 키를 두 번 쓰지 못하는지 확인한다
 * (docs/ERD.md "중복 요청 방지 기록 idempotency_key" 6.1절, 중복 불가 조합: 요청자 + 계약 버전 + 요청 키).
 *
 * <p>접수(PENDING) 단계에는 아직 응답이 없다. 업무 반영과 최종 응답은 같은 거래에서 나중에 채운다.
 */
@SpringBootTest
@ActiveProfiles({"db", "test"})
@Transactional
class IdempotencyRecordRepositoryTest {

    private static final UUID DEPARTMENT_ID = UUID.fromString("d0000000-0000-7000-8000-000000000099");
    private static final UUID MEMBER_ID = UUID.fromString("a0000000-0000-7000-8000-000000000099");
    private static final String CONTRACT = "hops-system-1";
    private static final String PATH = "/api/v2/inquiries/e0000000-0000-7000-8000-000000000099/messages";
    private static final String KEY = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final String HASH = "a".repeat(64);

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 테스트용_요청자를_넣는다() {
        jdbcTemplate.update("INSERT INTO department (id, code, name) VALUES (?, 'TEST_SALES', '테스트 영업팀')", DEPARTMENT_ID);
        jdbcTemplate.update("INSERT INTO member (id, login_id, employee_no, password_hash, name, department_id) "
                + "VALUES (?, 'test.requester', '29990099', 'hash', '테스트 문의자', ?)", MEMBER_ID, DEPARTMENT_ID);
    }

    @Test
    void 접수한_요청을_저장하고_같은_계약의_같은_키는_두_번_기록하지_않는다() {
        // DB는 마이크로초까지만 저장하므로 비교가 어긋나지 않게 잘라둔다
        Instant expiresAt = Instant.now().plus(24, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        UUID operationId = UUID.fromString("0c000000-0000-7000-8000-000000000001");
        IdempotencyRecord saved = idempotencyRecordRepository.save(newRecord(CONTRACT, "POST", operationId, expiresAt));
        entityManager.flush();
        entityManager.clear();

        IdempotencyRecord found = idempotencyRecordRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getActor().getId()).isEqualTo(MEMBER_ID);
        assertThat(found.getContractVersion()).isEqualTo(CONTRACT);
        assertThat(found.getHttpMethod()).isEqualTo("POST");
        assertThat(found.getRequestPath()).isEqualTo(PATH);
        assertThat(found.getIdempotencyKey()).isEqualTo(KEY);
        assertThat(found.getRequestHash()).isEqualTo(HASH);
        assertThat(found.getOperationId()).isEqualTo(operationId);
        assertThat(found.getOperationState()).isEqualTo(OperationState.PENDING);
        assertThat(found.getExpiresAt()).isEqualTo(expiresAt);
        // 접수 단계라 업무 대상·응답·만료 정리 표시가 아직 없다
        assertThat(found.getResourceType()).isNull();
        assertThat(found.getResourceId()).isNull();
        assertThat(found.getResponseStatus()).isNull();
        assertThat(found.getResponseBody()).isNull();
        assertThat(found.getTombstonedAt()).isNull();

        // 같은 요청자 + 같은 계약 + 같은 키 → 메서드·경로가 달라도 DB의 중복 불가 제약이 막는다
        assertThatThrownBy(() -> idempotencyRecordRepository.saveAndFlush(
                newRecord(CONTRACT, "PATCH", UUID.fromString("0c000000-0000-7000-8000-000000000002"), expiresAt)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private IdempotencyRecord newRecord(String contractVersion, String httpMethod, UUID operationId, Instant expiresAt) {
        Member actor = memberRepository.findById(MEMBER_ID).orElseThrow();
        return new IdempotencyRecord(actor, contractVersion, httpMethod, PATH, KEY, HASH, operationId,
                OperationState.PENDING, expiresAt);
    }
}
