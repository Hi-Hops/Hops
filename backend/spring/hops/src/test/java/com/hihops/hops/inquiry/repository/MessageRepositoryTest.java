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

import com.hihops.hops.inquiry.domain.Inquiry;
import com.hihops.hops.inquiry.domain.Message;
import com.hihops.hops.inquiry.domain.MessageStatus;
import com.hihops.hops.inquiry.domain.SenderType;

import jakarta.persistence.EntityManager;

/**
 * 대화 메시지(message)를 저장하고, 한 문의 안에서 같은 순번이 두 번 들어가지 않는지 확인한다
 * (docs/ERD.md "대화 메시지 message", 중복 불가 조합: 문의 ID + 메시지 순번).
 *
 * <p>payload(화면 표시 데이터)는 JSONB라서, 저장한 JSON을 그대로 다시 읽는지도 함께 본다.
 */
@SpringBootTest
@ActiveProfiles({"db", "test"})
@Transactional
class MessageRepositoryTest {

    private static final UUID DEPARTMENT_ID = UUID.fromString("d0000000-0000-7000-8000-000000000099");
    private static final UUID REQUESTER_ID = UUID.fromString("a0000000-0000-7000-8000-000000000099");
    private static final UUID INQUIRY_ID = UUID.fromString("e0000000-0000-7000-8000-000000000099");

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private InquiryRepository inquiryRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 테스트용_부서와_문의자와_문의를_넣는다() {
        jdbcTemplate.update("INSERT INTO department (id, code, name) VALUES (?, 'TEST_SALES', '테스트 영업팀')", DEPARTMENT_ID);
        jdbcTemplate.update("INSERT INTO member (id, login_id, employee_no, password_hash, name, department_id) "
                + "VALUES (?, 'test.requester', '29990099', 'hash', '테스트 문의자', ?)", REQUESTER_ID, DEPARTMENT_ID);
        jdbcTemplate.update("INSERT INTO inquiry (id, inquiry_number, requester_member_id, title, inquiry_status) "
                + "VALUES (?, 'Q-9999', ?, '결재 문서가 안 열려요', 'IN_PROGRESS')", INQUIRY_ID, REQUESTER_ID);
    }

    @Test
    void 메시지를_저장하고_같은_문의에_같은_순번은_막는다() {
        Inquiry inquiry = inquiryRepository.findById(INQUIRY_ID).orElseThrow();
        Message saved = messageRepository.save(new Message(inquiry, 1, SenderType.AI,
                "문서 번호를 알려주세요.", "{\"blocks\": [{\"type\": \"text\"}]}", MessageStatus.COMPLETED));
        entityManager.flush();
        entityManager.clear();

        Message found = messageRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getInquiry().getId()).isEqualTo(INQUIRY_ID);
        assertThat(found.getSequenceNo()).isEqualTo(1);
        assertThat(found.getSenderType()).isEqualTo(SenderType.AI);
        assertThat(found.getContent()).isEqualTo("문서 번호를 알려주세요.");
        assertThat(found.getMessageStatus()).isEqualTo(MessageStatus.COMPLETED);
        assertThat(found.getPayload()).isEqualToIgnoringWhitespace("{\"blocks\": [{\"type\": \"text\"}]}");

        // 같은 문의 + 같은 순번 → DB의 중복 불가 제약이 막는다 (대화 순서가 꼬이지 않게)
        Inquiry sameInquiry = inquiryRepository.findById(INQUIRY_ID).orElseThrow();
        assertThatThrownBy(() -> messageRepository.saveAndFlush(
                new Message(sameInquiry, 1, SenderType.USER, "중복 순번", null, MessageStatus.COMPLETED)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
