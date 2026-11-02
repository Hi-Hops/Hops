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
import com.hihops.hops.agent.domain.AgentTask;
import com.hihops.hops.agent.domain.RunStatus;
import com.hihops.hops.inquiry.domain.Inquiry;
import com.hihops.hops.inquiry.domain.Message;
import com.hihops.hops.inquiry.repository.InquiryRepository;
import com.hihops.hops.inquiry.repository.MessageRepository;

import jakarta.persistence.EntityManager;

/**
 * AI 실행(agent_run) 한 건을 저장하고 다시 읽는지 확인한다 (docs/ERD.md "AI 실행 agent_run").
 *
 * <p>상담 실행은 시작 메시지·실행 당시 문의 버전(inputVersion)·AI에 보낸 입력(inputSnapshot)을 남긴다.
 * 결과가 늦게 와서 문의 버전이 달라졌으면 Spring이 그 결과를 버리는 기준이 된다.
 */
@SpringBootTest
@ActiveProfiles({"db", "test"})
@Transactional
class AgentRunRepositoryTest {

    private static final UUID DEPARTMENT_ID = UUID.fromString("d0000000-0000-7000-8000-000000000099");
    private static final UUID REQUESTER_ID = UUID.fromString("a0000000-0000-7000-8000-000000000099");
    private static final UUID INQUIRY_ID = UUID.fromString("e0000000-0000-7000-8000-000000000099");
    private static final UUID MESSAGE_ID = UUID.fromString("f0000000-0000-7000-8000-000000000099");

    @Autowired
    private AgentRunRepository agentRunRepository;

    @Autowired
    private InquiryRepository inquiryRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 테스트용_문의와_시작_메시지를_넣는다() {
        jdbcTemplate.update("INSERT INTO department (id, code, name) VALUES (?, 'TEST_SALES', '테스트 영업팀')", DEPARTMENT_ID);
        jdbcTemplate.update("INSERT INTO member (id, login_id, employee_no, password_hash, name, department_id) "
                + "VALUES (?, 'test.requester', '29990099', 'hash', '테스트 문의자', ?)", REQUESTER_ID, DEPARTMENT_ID);
        jdbcTemplate.update("INSERT INTO inquiry (id, inquiry_number, requester_member_id, title, inquiry_status) "
                + "VALUES (?, 'Q-9999', ?, '결재 문서가 안 열려요', 'IN_PROGRESS')", INQUIRY_ID, REQUESTER_ID);
        jdbcTemplate.update("INSERT INTO message (id, inquiry_id, sequence_no, sender_type, content, message_status) "
                + "VALUES (?, ?, 1, 'USER', '결재 문서가 안 열려요', 'COMPLETED')", MESSAGE_ID, INQUIRY_ID);
    }

    @Test
    void 상담_실행을_저장하고_DB에서_다시_읽는다() {
        Inquiry inquiry = inquiryRepository.findById(INQUIRY_ID).orElseThrow();
        Message triggerMessage = messageRepository.findById(MESSAGE_ID).orElseThrow();
        // 접수만 된 대기(QUEUED) 실행이라 아직 시작 시각이 없다
        AgentRun saved = agentRunRepository.save(new AgentRun(inquiry, triggerMessage, 1, "{\"answerMessageId\": \"m-1\"}", "gpt-test", "prompt-v1", "schema-v1"));
        entityManager.flush();
        entityManager.clear();

        AgentRun found = agentRunRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getInquiry().getId()).isEqualTo(INQUIRY_ID);
        assertThat(found.getTriggerMessage().getId()).isEqualTo(MESSAGE_ID);
        assertThat(found.getTask()).isEqualTo(AgentTask.INQUIRY_ANALYSIS);
        assertThat(found.getRunStatus()).isEqualTo(RunStatus.QUEUED);
        assertThat(found.getInputVersion()).isEqualTo(1);
        assertThat(found.getModelVersion()).isEqualTo("gpt-test");
        assertThat(found.getPromptVersion()).isEqualTo("prompt-v1");
        assertThat(found.getSchemaVersion()).isEqualTo("schema-v1");
        assertThat(found.getInputSnapshot()).isEqualToIgnoringWhitespace("{\"answerMessageId\": \"m-1\"}");
        assertThat(found.getStartedAt()).isNull();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
        // 아직 Supervisor 경로·결과·종료 시각이 없다
        assertThat(found.getRoute()).isNull();
        assertThat(found.getResult()).isNull();
        assertThat(found.getCompletedAt()).isNull();
    }

    // PostgreSQL은 한 트랜잭션에서 오류가 나면 이후 명령을 거부하므로, 위반은 테스트마다 하나씩 확인한다
    @Test
    void 끝낸_실행을_저장하면_시작_종료_시각과_함께_DB에_남는다() {
        Inquiry inquiry = inquiryRepository.findById(INQUIRY_ID).orElseThrow();
        Message triggerMessage = messageRepository.findById(MESSAGE_ID).orElseThrow();
        AgentRun run = new AgentRun(inquiry, triggerMessage, 1, "{}", "gpt-test", "prompt-v1", "schema-v1");
        // DB는 마이크로초까지만 저장하므로 비교가 어긋나지 않게 잘라둔다
        Instant startedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant completedAt = startedAt.plusSeconds(30);
        run.start(startedAt);
        run.finish(RunStatus.SUCCEEDED, completedAt);

        // 상태 변경 메서드로 끝내면 ck_agent_run_timestamps에 걸리지 않고 저장된다
        AgentRun saved = agentRunRepository.saveAndFlush(run);
        entityManager.clear();

        AgentRun found = agentRunRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getRunStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(found.getStartedAt()).isEqualTo(startedAt);
        assertThat(found.getCompletedAt()).isEqualTo(completedAt);
    }

    @Test
    void 끝난_실행에_종료_시각이_없으면_DB가_거절한다() {
        // 끝난 실행(SUCCEEDED)인데 종료 시각이 없다
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO agent_run (id, inquiry_id, task, run_status, input_version, "
                + "input_snapshot, model_version, prompt_version, schema_version, started_at) "
                + "VALUES (gen_random_uuid(), ?, 'INQUIRY_ANALYSIS', 'SUCCEEDED', 1, '{}', 'm', 'p', 's', now())", INQUIRY_ID))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 대기_중인_실행에_시작_시각이_있으면_DB가_거절한다() {
        // 대기(QUEUED) 중인데 시작 시각이 있다
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO agent_run (id, inquiry_id, task, run_status, input_version, "
                + "input_snapshot, model_version, prompt_version, schema_version, started_at) "
                + "VALUES (gen_random_uuid(), ?, 'INQUIRY_ANALYSIS', 'QUEUED', 1, '{}', 'm', 'p', 's', now())", INQUIRY_ID))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
