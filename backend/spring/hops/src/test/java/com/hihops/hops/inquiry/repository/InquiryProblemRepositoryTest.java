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

import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.agent.repository.AgentRunRepository;
import com.hihops.hops.inquiry.domain.Inquiry;
import com.hihops.hops.inquiry.domain.InquiryProblem;
import com.hihops.hops.inquiry.domain.ProblemStatus;

import jakarta.persistence.EntityManager;

/**
 * 문의 문제(inquiry_problem)를 저장하고, 한 문의 안에서 같은 문제 번호가 두 번 들어가지 않는지 확인한다
 * (docs/ERD.md "문의 문제 inquiry_problem", 중복 불가 조합: 문의 ID + 문제 번호).
 *
 * <p>추가 질문 횟수(clarifyCount)는 0부터 시작한다. 문제당 질문 묶음 최대 5회를 지키는 기준이다.
 */
@SpringBootTest
@ActiveProfiles({"db", "test"})
@Transactional
class InquiryProblemRepositoryTest {

    private static final UUID DEPARTMENT_ID = UUID.fromString("d0000000-0000-7000-8000-000000000099");
    private static final UUID REQUESTER_ID = UUID.fromString("a0000000-0000-7000-8000-000000000099");
    private static final UUID INQUIRY_ID = UUID.fromString("e0000000-0000-7000-8000-000000000099");
    private static final UUID RUN_ID = UUID.fromString("b1000000-0000-7000-8000-000000000099");

    @Autowired
    private InquiryProblemRepository inquiryProblemRepository;

    @Autowired
    private InquiryRepository inquiryRepository;

    @Autowired
    private AgentRunRepository agentRunRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 테스트용_문의와_AI_실행을_넣는다() {
        jdbcTemplate.update("INSERT INTO department (id, code, name) VALUES (?, 'TEST_SALES', '테스트 영업팀')", DEPARTMENT_ID);
        jdbcTemplate.update("INSERT INTO member (id, login_id, employee_no, password_hash, name, department_id) "
                + "VALUES (?, 'test.requester', '29990099', 'hash', '테스트 문의자', ?)", REQUESTER_ID, DEPARTMENT_ID);
        jdbcTemplate.update("INSERT INTO inquiry (id, inquiry_number, requester_member_id, title, inquiry_status) "
                + "VALUES (?, 'Q-9999', ?, '결재 문서가 안 열려요', 'IN_PROGRESS')", INQUIRY_ID, REQUESTER_ID);
        jdbcTemplate.update("INSERT INTO agent_run (id, inquiry_id, task, run_status, input_version, input_snapshot, "
                + "model_version, prompt_version, schema_version, started_at) "
                + "VALUES (?, ?, 'INQUIRY_ANALYSIS', 'RUNNING', 1, '{}', 'gpt-test', 'prompt-v1', 'schema-v1', now())",
                RUN_ID, INQUIRY_ID);
    }

    @Test
    void 문제를_저장하고_같은_문의에_같은_문제_번호는_막는다() {
        Inquiry inquiry = inquiryRepository.findById(INQUIRY_ID).orElseThrow();
        AgentRun run = agentRunRepository.findById(RUN_ID).orElseThrow();
        InquiryProblem saved = inquiryProblemRepository.save(new InquiryProblem(inquiry, 1, "결재 문서 열람 오류",
                "결재함에서 문서를 누르면 빈 화면이 보인다", ProblemStatus.CLARIFYING, run));
        entityManager.flush();
        entityManager.clear();

        InquiryProblem found = inquiryProblemRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getInquiry().getId()).isEqualTo(INQUIRY_ID);
        assertThat(found.getProblemNo()).isEqualTo(1);
        assertThat(found.getTitle()).isEqualTo("결재 문서 열람 오류");
        assertThat(found.getDetail()).isEqualTo("결재함에서 문서를 누르면 빈 화면이 보인다");
        assertThat(found.getProblemStatus()).isEqualTo(ProblemStatus.CLARIFYING);
        assertThat(found.getClarifyCount()).isZero();
        assertThat(found.getAgentRun().getId()).isEqualTo(RUN_ID);
        // 티켓 생성 오류·해결 확인은 아직 없다
        assertThat(found.getTicketErrorCode()).isNull();
        assertThat(found.getCreationOutcome()).isNull();
        assertThat(found.getCreationInputVersion()).isNull();
        assertThat(found.getConfirmRequestedAt()).isNull();
        assertThat(found.getResolvedAt()).isNull();

        // 같은 문의 + 같은 문제 번호 → DB의 중복 불가 제약이 막는다
        Inquiry sameInquiry = inquiryRepository.findById(INQUIRY_ID).orElseThrow();
        assertThatThrownBy(() -> inquiryProblemRepository.saveAndFlush(new InquiryProblem(sameInquiry, 1,
                "중복 문제 번호", null, ProblemStatus.CLARIFYING, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
