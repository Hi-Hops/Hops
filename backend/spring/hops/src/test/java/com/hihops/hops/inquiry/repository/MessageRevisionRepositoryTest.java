package com.hihops.hops.inquiry.repository;

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

import com.hihops.hops.inquiry.domain.Message;
import com.hihops.hops.inquiry.domain.MessageRevision;

import jakarta.persistence.EntityManager;

/**
 * 메시지 수정 이력(message_revision)을 저장하고, 같은 메시지에 같은 수정 차수가 두 번 들어가지 않는지 확인한다
 * (docs/ERD.md "메시지 수정 이력 message_revision", 중복 불가 조합: 대화 메시지 ID + 수정 차수).
 *
 * <p>추가만 하는 이력 테이블이라 id와 생성일시만 자동으로 채워진다 (수정일시·삭제일시 없음).
 */
@SpringBootTest
@ActiveProfiles({"db", "test"})
@Transactional
class MessageRevisionRepositoryTest {

    private static final UUID DEPARTMENT_ID = UUID.fromString("d0000000-0000-7000-8000-000000000099");
    private static final UUID REQUESTER_ID = UUID.fromString("a0000000-0000-7000-8000-000000000099");
    private static final UUID INQUIRY_ID = UUID.fromString("e0000000-0000-7000-8000-000000000099");
    private static final UUID MESSAGE_ID = UUID.fromString("f0000000-0000-7000-8000-000000000099");

    @Autowired
    private MessageRevisionRepository messageRevisionRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 테스트용_문의와_사용자_메시지를_넣는다() {
        jdbcTemplate.update("INSERT INTO department (id, code, name) VALUES (?, 'TEST_SALES', '테스트 영업팀')", DEPARTMENT_ID);
        jdbcTemplate.update("INSERT INTO member (id, login_id, employee_no, password_hash, name, department_id) "
                + "VALUES (?, 'test.requester', '29990099', 'hash', '테스트 문의자', ?)", REQUESTER_ID, DEPARTMENT_ID);
        jdbcTemplate.update("INSERT INTO inquiry (id, inquiry_number, requester_member_id, title, inquiry_status) "
                + "VALUES (?, 'Q-9999', ?, '결재 문서가 안 열려요', 'IN_PROGRESS')", INQUIRY_ID, REQUESTER_ID);
        jdbcTemplate.update("INSERT INTO message (id, inquiry_id, sequence_no, sender_type, content, message_status) "
                + "VALUES (?, ?, 1, 'USER', '결재 문서가 안 열려요', 'COMPLETED')", MESSAGE_ID, INQUIRY_ID);
    }

    @Test
    void 수정_전_원문을_저장하고_같은_메시지에_같은_차수는_막는다() {
        Message message = messageRepository.findById(MESSAGE_ID).orElseThrow();
        MessageRevision saved = messageRevisionRepository.save(new MessageRevision(message, 1, "결재 문서가 안 열려요"));
        entityManager.flush();
        entityManager.clear();

        MessageRevision found = messageRevisionRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getMessage().getId()).isEqualTo(MESSAGE_ID);
        assertThat(found.getRevisionNo()).isEqualTo(1);
        assertThat(found.getContent()).isEqualTo("결재 문서가 안 열려요");
        assertThat(found.getCreatedAt()).isNotNull();

        // 같은 메시지 + 같은 수정 차수 → DB의 중복 불가 제약이 막는다
        Message sameMessage = messageRepository.findById(MESSAGE_ID).orElseThrow();
        assertThatThrownBy(() -> messageRevisionRepository.saveAndFlush(new MessageRevision(sameMessage, 1, "중복 차수")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
