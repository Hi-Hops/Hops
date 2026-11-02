package com.hihops.hops.ticket.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.agent.domain.AgentTask;
import com.hihops.hops.agent.domain.RunStatus;
import com.hihops.hops.agent.repository.AgentRunRepository;
import com.hihops.hops.inquiry.domain.Inquiry;
import com.hihops.hops.inquiry.repository.InquiryRepository;
import com.hihops.hops.ticket.domain.Ticket;
import com.hihops.hops.ticket.domain.TicketWorkLog;
import com.hihops.hops.ticket.domain.WorkLogType;
import com.hihops.hops.user.domain.Member;
import com.hihops.hops.user.repository.MemberRepository;

import jakarta.persistence.EntityManager;

/**
 * 티켓 처리 기록(ticket_work_log)을 저장하고, 해결 기록으로 시작한 Curator 실행이 그 기록을 가리키는지 확인한다
 * (docs/ERD.md "티켓 처리 기록 ticket_work_log", "AI 실행 agent_run").
 *
 * <p>추가만 하는 기록이다. 확인된 원인(confirmedCause)은 담당자가 근거로 확인한 경우에만 채우고, 모델이 채우지 않는다.
 */
@SpringBootTest
@ActiveProfiles({"db", "test"})
@Transactional
class TicketWorkLogRepositoryTest {

    private static final UUID DEPARTMENT_ID = UUID.fromString("d0000000-0000-7000-8000-000000000099");
    private static final UUID MEMBER_ID = UUID.fromString("a0000000-0000-7000-8000-000000000099");
    private static final UUID SYSTEM_ID = UUID.fromString("c0000000-0000-7000-8000-000000000099");
    private static final UUID INQUIRY_ID = UUID.fromString("e0000000-0000-7000-8000-000000000099");
    private static final UUID PROBLEM_ID = UUID.fromString("e1000000-0000-7000-8000-000000000099");
    private static final UUID REQUEST_ID = UUID.fromString("e2000000-0000-7000-8000-000000000099");
    private static final UUID RUN_ID = UUID.fromString("b1000000-0000-7000-8000-000000000099");
    private static final UUID TICKET_ID = UUID.fromString("e3000000-0000-7000-8000-000000000099");

    @Autowired
    private TicketWorkLogRepository ticketWorkLogRepository;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private InquiryRepository inquiryRepository;

    @Autowired
    private AgentRunRepository agentRunRepository;

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
    void 해결_기록을_저장하고_DB에서_다시_읽는다() {
        TicketWorkLog saved = ticketWorkLogRepository.save(newResolvedLog());
        entityManager.flush();
        entityManager.clear();

        TicketWorkLog found = ticketWorkLogRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getTicket().getId()).isEqualTo(TICKET_ID);
        assertThat(found.getAuthor().getId()).isEqualTo(MEMBER_ID);
        assertThat(found.getLogType()).isEqualTo(WorkLogType.RESOLVED);
        assertThat(found.getActionDetail()).isEqualTo("결재 문서 뷰어 캐시를 비움");
        assertThat(found.getConfirmedCause()).isEqualTo("뷰어 캐시 손상");
        assertThat(found.getVerificationResult()).isEqualTo("같은 문서를 다시 열어 정상 표시 확인");
        assertThat(found.getAppliedVersion()).isNull();
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void Curator_실행은_대상_티켓과_시작_처리_기록을_가리킨다() {
        TicketWorkLog workLog = ticketWorkLogRepository.saveAndFlush(newResolvedLog());
        Inquiry inquiry = inquiryRepository.findById(INQUIRY_ID).orElseThrow();
        Ticket ticket = ticketRepository.findById(TICKET_ID).orElseThrow();

        AgentRun saved = agentRunRepository.save(new AgentRun(inquiry, ticket, workLog, RunStatus.RUNNING, 1,
                "{}", "gpt-test", "prompt-v1", "schema-v1", Instant.now()));
        entityManager.flush();
        entityManager.clear();

        AgentRun found = agentRunRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getTask()).isEqualTo(AgentTask.CURATION);
        assertThat(found.getTicket().getId()).isEqualTo(TICKET_ID);
        assertThat(found.getTriggerWorkLog().getId()).isEqualTo(workLog.getId());
        // Curator 실행에는 시작 메시지가 없다
        assertThat(found.getTriggerMessage()).isNull();
    }

    @Test
    void 검증_결과가_없는_해결_기록은_DB가_거절한다() {
        Ticket ticket = ticketRepository.findById(TICKET_ID).orElseThrow();
        Member author = memberRepository.findById(MEMBER_ID).orElseThrow();

        // 검증하지 않은 조치를 해결 완료로 남기지 않는다 (ck_ticket_work_log_verified)
        assertThatThrownBy(() -> ticketWorkLogRepository.saveAndFlush(new TicketWorkLog(ticket, author,
                WorkLogType.RESOLVED, "뷰어 캐시를 비움", null, "  ", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private TicketWorkLog newResolvedLog() {
        Ticket ticket = ticketRepository.findById(TICKET_ID).orElseThrow();
        Member author = memberRepository.findById(MEMBER_ID).orElseThrow();
        return new TicketWorkLog(ticket, author, WorkLogType.RESOLVED, "결재 문서 뷰어 캐시를 비움", "뷰어 캐시 손상",
                "같은 문서를 다시 열어 정상 표시 확인", null);
    }
}
