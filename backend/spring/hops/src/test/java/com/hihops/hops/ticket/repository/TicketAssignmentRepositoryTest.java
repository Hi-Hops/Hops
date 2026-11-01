package com.hihops.hops.ticket.repository;

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

import com.hihops.hops.itsystem.domain.ItSystem;
import com.hihops.hops.itsystem.repository.ItSystemRepository;
import com.hihops.hops.ticket.domain.AssignmentSource;
import com.hihops.hops.ticket.domain.Ticket;
import com.hihops.hops.ticket.domain.TicketAssignment;
import com.hihops.hops.user.domain.Member;
import com.hihops.hops.user.repository.MemberRepository;

import jakarta.persistence.EntityManager;

/**
 * 담당 배정 이력(ticket_assignment)을 저장하고, 현재 배정(ended_at이 NULL)이 티켓당 1건만 있는지 확인한다
 * (docs/ERD.md "담당 배정 이력 ticket_assignment").
 *
 * <p>끝난 배정(ended_at이 있음)은 여러 건 쌓일 수 있다. 수동 정정은 현재 배정을 끝내고 새 배정을 추가하기 때문이다.
 */
@SpringBootTest
@ActiveProfiles({"db", "test"})
@Transactional
class TicketAssignmentRepositoryTest {

    private static final UUID DEPARTMENT_ID = UUID.fromString("d0000000-0000-7000-8000-000000000099");
    private static final UUID MEMBER_ID = UUID.fromString("a0000000-0000-7000-8000-000000000099");
    private static final UUID SYSTEM_ID = UUID.fromString("c0000000-0000-7000-8000-000000000099");
    private static final UUID INQUIRY_ID = UUID.fromString("e0000000-0000-7000-8000-000000000099");
    private static final UUID PROBLEM_ID = UUID.fromString("e1000000-0000-7000-8000-000000000099");
    private static final UUID REQUEST_ID = UUID.fromString("e2000000-0000-7000-8000-000000000099");
    private static final UUID TICKET_ID = UUID.fromString("e3000000-0000-7000-8000-000000000099");

    @Autowired
    private TicketAssignmentRepository ticketAssignmentRepository;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private ItSystemRepository itSystemRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 테스트용_티켓과_끝난_배정_한_건을_넣는다() {
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
        jdbcTemplate.update("INSERT INTO ticket (id, ticket_number, inquiry_id, inquiry_problem_id, standard_request_id, "
                + "title, detail, work_type, responsible_system_id, assignee_member_id) "
                + "VALUES (?, 'TK-9999', ?, ?, ?, '결재 문서 열람 오류', '빈 화면', 'INVESTIGATION', ?, ?)",
                TICKET_ID, INQUIRY_ID, PROBLEM_ID, REQUEST_ID, SYSTEM_ID, MEMBER_ID);
        // 이미 끝난(ended_at 있음) 예전 배정 한 건. 현재 배정 1건 규칙에는 걸리지 않아야 한다
        jdbcTemplate.update("INSERT INTO ticket_assignment (ticket_id, it_system_id, assignee_member_id, source, reason, "
                + "assigned_at, ended_at) VALUES (?, ?, ?, 'AUTO', '예전 자동 배정', now(), now())",
                TICKET_ID, SYSTEM_ID, MEMBER_ID);
    }

    @Test
    void 현재_배정은_티켓당_한_건만_저장된다() {
        // DB는 마이크로초까지만 저장하므로 비교가 어긋나지 않게 잘라둔다
        Instant assignedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        TicketAssignment saved = ticketAssignmentRepository.save(newCurrentAssignment(assignedAt));
        entityManager.flush();
        entityManager.clear();

        TicketAssignment found = ticketAssignmentRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getTicket().getId()).isEqualTo(TICKET_ID);
        assertThat(found.getItSystem().getId()).isEqualTo(SYSTEM_ID);
        assertThat(found.getAssignee().getId()).isEqualTo(MEMBER_ID);
        assertThat(found.getSource()).isEqualTo(AssignmentSource.MANUAL_CORRECTION);
        assertThat(found.getAssignedBy().getId()).isEqualTo(MEMBER_ID);
        assertThat(found.getReason()).isEqualTo("결재 문서 권한은 전자결재 시스템 담당");
        assertThat(found.getAssignedAt()).isEqualTo(assignedAt);
        assertThat(found.getEndedAt()).isNull();

        // 끝나지 않은 배정이 이미 있는데 또 추가하면 → DB의 부분 유일 인덱스가 막는다 (현재 담당이 둘이 되지 않게)
        assertThatThrownBy(() -> ticketAssignmentRepository.saveAndFlush(newCurrentAssignment(assignedAt)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private TicketAssignment newCurrentAssignment(Instant assignedAt) {
        Ticket ticket = ticketRepository.findById(TICKET_ID).orElseThrow();
        ItSystem system = itSystemRepository.findById(SYSTEM_ID).orElseThrow();
        Member member = memberRepository.findById(MEMBER_ID).orElseThrow();
        return new TicketAssignment(ticket, system, member, AssignmentSource.MANUAL_CORRECTION, member,
                "결재 문서 권한은 전자결재 시스템 담당", assignedAt);
    }
}
