package com.hihops.hops.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.hihops.hops.inquiry.domain.Inquiry;
import com.hihops.hops.inquiry.repository.InquiryRepository;
import com.hihops.hops.notification.domain.Notification;
import com.hihops.hops.notification.domain.NotificationType;
import com.hihops.hops.user.domain.Member;
import com.hihops.hops.user.repository.MemberRepository;

import jakarta.persistence.EntityManager;

/**
 * 알림(notification)을 저장하고 다시 읽는지 확인한다 (docs/ERD.md "알림 notification").
 *
 * <p>문의자 알림은 누르면 이동할 문의를 가리키고, 담당자 알림은 티켓을 가리킨다. 이동할 곳이 없으면 비워둘 수 있다.
 */
@SpringBootTest
@ActiveProfiles({"db", "test"})
@Transactional
class NotificationRepositoryTest {

    private static final UUID DEPARTMENT_ID = UUID.fromString("d0000000-0000-7000-8000-000000000099");
    private static final UUID MEMBER_ID = UUID.fromString("a0000000-0000-7000-8000-000000000099");
    private static final UUID INQUIRY_ID = UUID.fromString("e0000000-0000-7000-8000-000000000099");

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private InquiryRepository inquiryRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 테스트용_문의자와_문의를_넣는다() {
        jdbcTemplate.update("INSERT INTO department (id, code, name) VALUES (?, 'TEST_SALES', '테스트 영업팀')", DEPARTMENT_ID);
        jdbcTemplate.update("INSERT INTO member (id, login_id, employee_no, password_hash, name, department_id) "
                + "VALUES (?, 'test.requester', '29990099', 'hash', '테스트 문의자', ?)", MEMBER_ID, DEPARTMENT_ID);
        jdbcTemplate.update("INSERT INTO inquiry (id, inquiry_number, requester_member_id, title, inquiry_status) "
                + "VALUES (?, 'Q-9999', ?, '결재 문서가 안 열려요', 'IN_PROGRESS')", INQUIRY_ID, MEMBER_ID);
    }

    @Test
    void 문의자에게_보낸_해결_확인_요청_알림을_저장하고_다시_읽는다() {
        Member recipient = memberRepository.findById(MEMBER_ID).orElseThrow();
        Inquiry inquiry = inquiryRepository.findById(INQUIRY_ID).orElseThrow();
        Notification saved = notificationRepository.save(new Notification(recipient, inquiry, null,
                NotificationType.CONFIRM_REQUESTED, "Q-9999 해결 확인을 부탁드려요", "{\"inquiryNumber\": \"Q-9999\"}"));
        entityManager.flush();
        entityManager.clear();

        Notification found = notificationRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getRecipient().getId()).isEqualTo(MEMBER_ID);
        assertThat(found.getInquiry().getId()).isEqualTo(INQUIRY_ID);
        assertThat(found.getTicket()).isNull();
        assertThat(found.getNotificationType()).isEqualTo(NotificationType.CONFIRM_REQUESTED);
        assertThat(found.getTitle()).isEqualTo("Q-9999 해결 확인을 부탁드려요");
        assertThat(found.getPayload()).isEqualToIgnoringWhitespace("{\"inquiryNumber\": \"Q-9999\"}");
        assertThat(found.getCreatedAt()).isNotNull();
    }
}
