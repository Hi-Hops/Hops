package com.hihops.hops.agent.repository;

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

import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.agent.domain.AgentStep;
import com.hihops.hops.agent.domain.AgentType;

import jakarta.persistence.EntityManager;

/**
 * AI 실행 단계(agent_step)를 저장하고, 한 실행 안에서 같은 단계 순번이 두 번 들어가지 않는지 확인한다
 * (docs/ERD.md "AI 실행 단계 agent_step", 중복 불가 조합: 실행 ID + 단계 순번).
 *
 * <p>추가만 하는 기록이라 시도 횟수(attemptCount)는 처음 저장할 때 1로 시작한다.
 */
@SpringBootTest
@ActiveProfiles({"db", "test"})
@Transactional
class AgentStepRepositoryTest {

    private static final UUID DEPARTMENT_ID = UUID.fromString("d0000000-0000-7000-8000-000000000099");
    private static final UUID REQUESTER_ID = UUID.fromString("a0000000-0000-7000-8000-000000000099");
    private static final UUID INQUIRY_ID = UUID.fromString("e0000000-0000-7000-8000-000000000099");
    private static final UUID RUN_ID = UUID.fromString("b1000000-0000-7000-8000-000000000099");

    @Autowired
    private AgentStepRepository agentStepRepository;

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
    void 실행_단계를_저장하고_같은_실행에_같은_순번은_막는다() {
        AgentRun run = agentRunRepository.findById(RUN_ID).orElseThrow();
        // DB는 마이크로초까지만 저장하므로 비교가 어긋나지 않게 잘라둔다
        Instant startedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        AgentStep saved = agentStepRepository.save(new AgentStep(run, 1, AgentType.KNOWLEDGE, "SEARCH",
                "SUCCEEDED", "문서 검색 중…", startedAt));
        entityManager.flush();
        entityManager.clear();

        AgentStep found = agentStepRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getAgentRun().getId()).isEqualTo(RUN_ID);
        assertThat(found.getSequenceNo()).isEqualTo(1);
        assertThat(found.getAgentType()).isEqualTo(AgentType.KNOWLEDGE);
        assertThat(found.getStepType()).isEqualTo("SEARCH");
        assertThat(found.getStepStatus()).isEqualTo("SUCCEEDED");
        assertThat(found.getDisplaySummary()).isEqualTo("문서 검색 중…");
        assertThat(found.getAttemptCount()).isEqualTo(1);
        assertThat(found.getStartedAt()).isEqualTo(startedAt);
        assertThat(found.getCreatedAt()).isNotNull();
        // 도구 호출·근거·입출력·오류·종료 시각은 아직 없다
        assertThat(found.getToolName()).isNull();
        assertThat(found.getStepData()).isNull();
        assertThat(found.getCompletedAt()).isNull();

        // 같은 실행 + 같은 단계 순번 → DB의 중복 불가 제약이 막는다 (타임라인 순서가 꼬이지 않게)
        AgentRun sameRun = agentRunRepository.findById(RUN_ID).orElseThrow();
        assertThatThrownBy(() -> agentStepRepository.saveAndFlush(new AgentStep(sameRun, 1, AgentType.SUPERVISOR,
                "INTAKE", "SUCCEEDED", "문의 확인 중…", startedAt)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
