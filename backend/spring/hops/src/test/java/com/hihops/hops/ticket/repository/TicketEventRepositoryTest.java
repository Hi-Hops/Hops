package com.hihops.hops.ticket.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.hihops.hops.ticket.domain.ActorType;
import com.hihops.hops.ticket.domain.Ticket;
import com.hihops.hops.ticket.domain.TicketEvent;
import com.hihops.hops.ticket.domain.TicketEventType;
import com.hihops.hops.user.domain.Member;
import com.hihops.hops.user.repository.MemberRepository;

import jakarta.persistence.EntityManager;

/**
 * 티켓 변경 이력(ticket_event)을 저장하고 다시 읽는지 확인한다 (docs/ERD.md "티켓 변경 이력 ticket_event").
 *
 * <p>추가만 하는 이력이다. 사람이 처리 유형을 바꾸면 누가(actorMember)·무엇을(before/after)·왜(reason) 바꿨는지 남긴다.
 */
@SpringBootTest
@ActiveProfiles({"db", "test"})
@Transactional
class TicketEventRepositoryTest {

    private static final UUID DEPARTMENT_ID = UUID.fromString("d0000000-0000-7000-8000-000000000099");
    private static final UUID MEMBER_ID = UUID.fromString("a0000000-0000-7000-8000-000000000099");
    private static final UUID SYSTEM_ID = UUID.fromString("c0000000-0000-7000-8000-000000000099");
    private static final UUID INQUIRY_ID = UUID.fromString("e0000000-0000-7000-8000-000000000099");
    private static final UUID PROBLEM_ID = UUID.fromString("e1000000-0000-7000-8000-000000000099");
    private static final UUID REQUEST_ID = UUID.fromString("e2000000-0000-7000-8000-000000000099");
    private static final UUID RUN_ID = UUID.fromString("b1000000-0000-7000-8000-000000000099");
    private static final UUID TICKET_ID = UUID.fromString("e3000000-0000-7000-8000-000000000099");

    @Autowired
    private TicketEventRepository ticketEventRepository;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 테스트용_티켓을_넣는다() {
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
        jdbcTemplate.update("INSERT INTO agent_run (id, inquiry_id, task, run_status, input_version, input_snapshot, "
                + "model_version, prompt_version, schema_version, started_at, completed_at) "
                + "VALUES (?, ?, 'INQUIRY_ANALYSIS', 'SUCCEEDED', 1, '{}', 'gpt-test', 'prompt-v1', 'schema-v1', now(), now())",
                RUN_ID, INQUIRY_ID);
        jdbcTemplate.update("INSERT INTO standard_request (id, inquiry_problem_id, inquiry_version, agent_run_id, intent, "
                + "work_type, responsible_system_id, summary) "
                + "VALUES (?, ?, 1, ?, 'OBSERVED_SYMPTOM', 'INVESTIGATION', ?, '빈 화면')",
                REQUEST_ID, PROBLEM_ID, RUN_ID, SYSTEM_ID);
        jdbcTemplate.update("INSERT INTO ticket (id, ticket_number, inquiry_id, inquiry_problem_id, standard_request_id, "
                + "title, detail, work_type, responsible_system_id, assignee_member_id) "
                + "VALUES (?, 'TK-9999', ?, ?, ?, '결재 문서 열람 오류', '빈 화면', 'INVESTIGATION', ?, ?)",
                TICKET_ID, INQUIRY_ID, PROBLEM_ID, REQUEST_ID, SYSTEM_ID, MEMBER_ID);
    }

    @Test
    void 담당자의_처리_유형_변경_이력을_저장하고_다시_읽는다() {
        Ticket ticket = ticketRepository.findById(TICKET_ID).orElseThrow();
        Member actor = memberRepository.findById(MEMBER_ID).orElseThrow();
        TicketEvent saved = ticketEventRepository.save(new TicketEvent(ticket, TicketEventType.WORK_TYPE_CHANGED,
                ActorType.MEMBER, actor, null, "{\"workType\": \"INVESTIGATION\"}", "{\"workType\": \"INCIDENT\"}",
                "여러 부서에서 같은 오류가 확인돼 장애로 확정"));
        entityManager.flush();
        entityManager.clear();

        TicketEvent found = ticketEventRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getTicket().getId()).isEqualTo(TICKET_ID);
        assertThat(found.getEventType()).isEqualTo(TicketEventType.WORK_TYPE_CHANGED);
        assertThat(found.getActorType()).isEqualTo(ActorType.MEMBER);
        assertThat(found.getActorMember().getId()).isEqualTo(MEMBER_ID);
        assertThat(found.getAgentRun()).isNull();
        assertThat(found.getBeforeData()).isEqualToIgnoringWhitespace("{\"workType\": \"INVESTIGATION\"}");
        assertThat(found.getAfterData()).isEqualToIgnoringWhitespace("{\"workType\": \"INCIDENT\"}");
        assertThat(found.getReason()).isEqualTo("여러 부서에서 같은 오류가 확인돼 장애로 확정");
        assertThat(found.getCreatedAt()).isNotNull();
    }
}
