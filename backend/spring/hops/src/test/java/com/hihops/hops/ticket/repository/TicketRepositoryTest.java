package com.hihops.hops.ticket.repository;

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
import com.hihops.hops.inquiry.domain.InquiryProblem;
import com.hihops.hops.inquiry.domain.StandardRequest;
import com.hihops.hops.inquiry.repository.InquiryProblemRepository;
import com.hihops.hops.inquiry.repository.InquiryRepository;
import com.hihops.hops.inquiry.repository.StandardRequestRepository;
import com.hihops.hops.itsystem.domain.ItSystem;
import com.hihops.hops.itsystem.repository.ItSystemRepository;
import com.hihops.hops.ticket.domain.Ticket;
import com.hihops.hops.ticket.domain.TicketStatus;
import com.hihops.hops.ticket.domain.TicketWorkType;
import com.hihops.hops.user.domain.Member;
import com.hihops.hops.user.repository.MemberRepository;

import jakarta.persistence.EntityManager;

/**
 * 티켓(ticket)을 저장하고, 문제 하나에서 티켓이 두 번 생기지 않는지 확인한다
 * (docs/ERD.md "티켓 ticket", 중복 불가: 문제 ID).
 *
 * <p>새 티켓은 처리 상태 OPEN, 버전 0으로 시작한다.
 */
@SpringBootTest
@ActiveProfiles({"db", "test"})
@Transactional
class TicketRepositoryTest {

    private static final UUID DEPARTMENT_ID = UUID.fromString("d0000000-0000-7000-8000-000000000099");
    private static final UUID MEMBER_ID = UUID.fromString("a0000000-0000-7000-8000-000000000099");
    private static final UUID SYSTEM_ID = UUID.fromString("c0000000-0000-7000-8000-000000000099");
    private static final UUID INQUIRY_ID = UUID.fromString("e0000000-0000-7000-8000-000000000099");
    private static final UUID PROBLEM_ID = UUID.fromString("e1000000-0000-7000-8000-000000000099");
    private static final UUID REQUEST_ID = UUID.fromString("e2000000-0000-7000-8000-000000000099");

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private InquiryRepository inquiryRepository;

    @Autowired
    private InquiryProblemRepository inquiryProblemRepository;

    @Autowired
    private StandardRequestRepository standardRequestRepository;

    @Autowired
    private ItSystemRepository itSystemRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 테스트용_시스템과_문의_문제와_요청서를_넣는다() {
        jdbcTemplate.update("INSERT INTO department (id, code, name) VALUES (?, 'TEST_IT', '테스트 IT팀')", DEPARTMENT_ID);
        // 테스트를 짧게 하려고 한 회원이 문의자와 시스템 주담당자를 함께 맡는다
        jdbcTemplate.update("INSERT INTO member (id, login_id, employee_no, password_hash, name, department_id) "
                + "VALUES (?, 'test.member', '29990099', 'hash', '테스트 회원', ?)", MEMBER_ID, DEPARTMENT_ID);
        jdbcTemplate.update("INSERT INTO it_system (id, code, name, primary_owner_member_id) "
                + "VALUES (?, 'TEST_APPROVAL', '테스트 전자결재', ?)", SYSTEM_ID, MEMBER_ID);
        jdbcTemplate.update("INSERT INTO inquiry (id, inquiry_number, requester_member_id, title, inquiry_status) "
                + "VALUES (?, 'Q-9999', ?, '결재 문서가 안 열려요', 'IN_PROGRESS')", INQUIRY_ID, MEMBER_ID);
        jdbcTemplate.update("INSERT INTO inquiry_problem (id, inquiry_id, problem_no, title, problem_status) "
                + "VALUES (?, ?, 1, '결재 문서 열람 오류', 'TICKET_CREATED')", PROBLEM_ID, INQUIRY_ID);
        jdbcTemplate.update("INSERT INTO standard_request (id, inquiry_problem_id, inquiry_version, intent, work_type, "
                + "responsible_system_id, summary) VALUES (?, ?, 1, 'OBSERVED_SYMPTOM', 'INVESTIGATION', ?, '빈 화면')",
                REQUEST_ID, PROBLEM_ID, SYSTEM_ID);
    }

    @Test
    void 티켓을_저장하고_같은_문제에_티켓을_두_번_만들지_않는다() {
        Ticket saved = ticketRepository.save(newTicket("TK-9999"));
        entityManager.flush();
        entityManager.clear();

        Ticket found = ticketRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getTicketNumber()).isEqualTo("TK-9999");
        assertThat(found.getInquiry().getId()).isEqualTo(INQUIRY_ID);
        assertThat(found.getInquiryProblem().getId()).isEqualTo(PROBLEM_ID);
        assertThat(found.getStandardRequest().getId()).isEqualTo(REQUEST_ID);
        assertThat(found.getTitle()).isEqualTo("결재 문서 열람 오류");
        assertThat(found.getDetail()).isEqualTo("결재함에서 문서를 열면 빈 화면이 보인다");
        assertThat(found.getWorkType()).isEqualTo(TicketWorkType.INVESTIGATION);
        assertThat(found.getResponsibleSystem().getId()).isEqualTo(SYSTEM_ID);
        assertThat(found.getAssignee().getId()).isEqualTo(MEMBER_ID);
        // 새 티켓은 OPEN·버전 0으로 시작하고 아직 확인 요청·해결 시각이 없다
        assertThat(found.getTicketStatus()).isEqualTo(TicketStatus.OPEN);
        assertThat(found.getVersion()).isZero();
        assertThat(found.getConfirmRequestedAt()).isNull();
        assertThat(found.getResolvedAt()).isNull();

        // 같은 문제로 티켓을 또 만들면 → DB의 중복 불가 제약이 막는다 (문제당 티켓 최대 1개)
        assertThatThrownBy(() -> ticketRepository.saveAndFlush(newTicket("TK-9998")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Ticket newTicket(String ticketNumber) {
        Inquiry inquiry = inquiryRepository.findById(INQUIRY_ID).orElseThrow();
        InquiryProblem problem = inquiryProblemRepository.findById(PROBLEM_ID).orElseThrow();
        StandardRequest request = standardRequestRepository.findById(REQUEST_ID).orElseThrow();
        ItSystem system = itSystemRepository.findById(SYSTEM_ID).orElseThrow();
        Member assignee = memberRepository.findById(MEMBER_ID).orElseThrow();
        return new Ticket(ticketNumber, inquiry, problem, request, "결재 문서 열람 오류",
                "결재함에서 문서를 열면 빈 화면이 보인다", TicketWorkType.INVESTIGATION, system, assignee);
    }
}
