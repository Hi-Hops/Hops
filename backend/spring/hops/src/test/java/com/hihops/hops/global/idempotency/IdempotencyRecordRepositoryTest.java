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
 * 중복 요청 방지 기록(idempotency_key)을 저장하고, 같은 사람이 같은 API에 같은 키로 보낸 요청이 두 번 기록되지 않는지 확인한다
 * (docs/ERD.md "중복 요청 방지 기록 idempotency_key", 중복 불가 조합: 요청자 + 요청 방식 + 요청 경로 + 요청 키).
 */
@SpringBootTest
@ActiveProfiles({"db", "test"})
@Transactional
class IdempotencyRecordRepositoryTest {

    private static final UUID DEPARTMENT_ID = UUID.fromString("d0000000-0000-7000-8000-000000000099");
    private static final UUID MEMBER_ID = UUID.fromString("a0000000-0000-7000-8000-000000000099");
    private static final String PATH = "/api/inquiries/e0000000-0000-7000-8000-000000000099/messages";
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
    void 처음_처리한_응답을_저장하고_같은_요청은_두_번_기록하지_않는다() {
        // DB는 마이크로초까지만 저장하므로 비교가 어긋나지 않게 잘라둔다
        Instant expiresAt = Instant.now().plus(24, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        IdempotencyRecord saved = idempotencyRecordRepository.save(newRecord(expiresAt));
        entityManager.flush();
        entityManager.clear();

        IdempotencyRecord found = idempotencyRecordRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getActor().getId()).isEqualTo(MEMBER_ID);
        assertThat(found.getHttpMethod()).isEqualTo("POST");
        assertThat(found.getRequestPath()).isEqualTo(PATH);
        assertThat(found.getIdempotencyKey()).isEqualTo(KEY);
        assertThat(found.getRequestHash()).isEqualTo(HASH);
        assertThat(found.getResponseStatus()).isEqualTo(200);
        assertThat(found.getResponseBody()).isEqualToIgnoringWhitespace("{\"messageId\": \"m-1\"}");
        assertThat(found.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(found.getCreatedAt()).isNotNull();

        // 같은 요청자 + 같은 방식 + 같은 경로 + 같은 키 → DB의 중복 불가 제약이 막는다 (같은 키가 동시에 와도 하나만 저장)
        assertThatThrownBy(() -> idempotencyRecordRepository.saveAndFlush(newRecord(expiresAt)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private IdempotencyRecord newRecord(Instant expiresAt) {
        Member actor = memberRepository.findById(MEMBER_ID).orElseThrow();
        return new IdempotencyRecord(actor, "POST", PATH, KEY, HASH, 200, "{\"messageId\": \"m-1\"}", expiresAt);
    }
}
