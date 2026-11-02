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
import com.hihops.hops.inquiry.domain.InquiryProblem;
import com.hihops.hops.inquiry.domain.RequestIntent;
import com.hihops.hops.inquiry.domain.StandardRequest;
import com.hihops.hops.inquiry.domain.WorkType;
import com.hihops.hops.itsystem.domain.ItSystem;
import com.hihops.hops.itsystem.domain.SystemFeature;
import com.hihops.hops.itsystem.repository.ItSystemRepository;
import com.hihops.hops.itsystem.repository.SystemFeatureRepository;

import jakarta.persistence.EntityManager;

/**
 * 표준 요청서(standard_request)를 저장하고, 같은 문제·문의 버전·AI 실행의 요청서가 두 번 들어가지 않는지 확인한다
 * (docs/ERD.md "표준 요청서 standard_request" 6.1절, 중복 불가 조합: 문제 ID + 문의 버전 + AI 실행 ID).
 *
 * <p>같은 문의 버전이라도 새 AI 실행(재분석)이면 새 스냅샷을 추가할 수 있다. 옛 스냅샷은 덮어쓰지 않는다.
 *
 * <p>JSONB 목록 컬럼은 따로 넣지 않으면 빈 목록([])·빈 객체({})로 저장돼야 한다.
 */
@SpringBootTest
@ActiveProfiles({"db", "test"})
@Transactional
class StandardRequestRepositoryTest {

    private static final UUID DEPARTMENT_ID = UUID.fromString("d0000000-0000-7000-8000-000000000099");
    private static final UUID MEMBER_ID = UUID.fromString("a0000000-0000-7000-8000-000000000099");
    private static final UUID SYSTEM_ID = UUID.fromString("c0000000-0000-7000-8000-000000000099");
    private static final UUID FEATURE_ID = UUID.fromString("c1000000-0000-7000-8000-000000000099");
    private static final UUID INQUIRY_ID = UUID.fromString("e0000000-0000-7000-8000-000000000099");
    private static final UUID RUN_ID = UUID.fromString("b1000000-0000-7000-8000-000000000099");
    private static final UUID PROBLEM_ID = UUID.fromString("e1000000-0000-7000-8000-000000000099");
    private static final UUID NEXT_RUN_ID = UUID.fromString("b2000000-0000-7000-8000-000000000099");

    @Autowired
    private StandardRequestRepository standardRequestRepository;

    @Autowired
    private InquiryProblemRepository inquiryProblemRepository;

    @Autowired
    private AgentRunRepository agentRunRepository;

    @Autowired
    private ItSystemRepository itSystemRepository;

    @Autowired
    private SystemFeatureRepository systemFeatureRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 테스트용_시스템과_문의_문제를_넣는다() {
        jdbcTemplate.update("INSERT INTO department (id, code, name) VALUES (?, 'TEST_IT', '테스트 IT팀')", DEPARTMENT_ID);
        // 테스트를 짧게 하려고 한 회원이 문의자와 시스템 주담당자를 함께 맡는다
        jdbcTemplate.update("INSERT INTO member (id, login_id, employee_no, password_hash, name, department_id) "
                + "VALUES (?, 'test.member', '29990099', 'hash', '테스트 회원', ?)", MEMBER_ID, DEPARTMENT_ID);
        jdbcTemplate.update("INSERT INTO it_system (id, code, name, primary_owner_member_id) "
                + "VALUES (?, 'TEST_APPROVAL', '테스트 전자결재', ?)", SYSTEM_ID, MEMBER_ID);
        jdbcTemplate.update("INSERT INTO system_feature (id, it_system_id, code, name) "
                + "VALUES (?, ?, 'DOCUMENT_VIEW', '문서 조회')", FEATURE_ID, SYSTEM_ID);
        jdbcTemplate.update("INSERT INTO inquiry (id, inquiry_number, requester_member_id, title, inquiry_status) "
                + "VALUES (?, 'Q-9999', ?, '결재 문서가 안 열려요', 'IN_PROGRESS')", INQUIRY_ID, MEMBER_ID);
        jdbcTemplate.update("INSERT INTO agent_run (id, inquiry_id, task, run_status, input_version, input_snapshot, "
                + "model_version, prompt_version, schema_version, started_at) "
                + "VALUES (?, ?, 'INQUIRY_ANALYSIS', 'RUNNING', 1, '{}', 'gpt-test', 'prompt-v1', 'schema-v1', now())",
                RUN_ID, INQUIRY_ID);
        jdbcTemplate.update("INSERT INTO inquiry_problem (id, inquiry_id, problem_no, title, problem_status) "
                + "VALUES (?, ?, 1, '결재 문서 열람 오류', 'CLARIFYING')", PROBLEM_ID, INQUIRY_ID);
    }

    @Test
    void 요청서를_저장하고_같은_문제_버전_실행의_요청서는_막는다() {
        InquiryProblem problem = inquiryProblemRepository.findById(PROBLEM_ID).orElseThrow();
        AgentRun run = agentRunRepository.findById(RUN_ID).orElseThrow();
        ItSystem system = itSystemRepository.findById(SYSTEM_ID).orElseThrow();
        SystemFeature feature = systemFeatureRepository.findById(FEATURE_ID).orElseThrow();

        StandardRequest saved = standardRequestRepository.save(new StandardRequest(problem, 1, run,
                RequestIntent.OBSERVED_SYMPTOM, WorkType.INVESTIGATION, system, feature, system,
                "결재함에서 문서를 열면 빈 화면이 보인다"));
        entityManager.flush();
        entityManager.clear();

        StandardRequest found = standardRequestRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getInquiryProblem().getId()).isEqualTo(PROBLEM_ID);
        assertThat(found.getInquiryVersion()).isEqualTo(1);
        assertThat(found.getAgentRun().getId()).isEqualTo(RUN_ID);
        assertThat(found.getIntent()).isEqualTo(RequestIntent.OBSERVED_SYMPTOM);
        assertThat(found.getWorkType()).isEqualTo(WorkType.INVESTIGATION);
        assertThat(found.getReportedSystem().getId()).isEqualTo(SYSTEM_ID);
        assertThat(found.getReportedFeature().getId()).isEqualTo(FEATURE_ID);
        assertThat(found.getResponsibleSystem().getId()).isEqualTo(SYSTEM_ID);
        assertThat(found.getSummary()).isEqualTo("결재함에서 문서를 열면 빈 화면이 보인다");
        assertThat(found.getCreatedAt()).isNotNull();
        // 따로 넣지 않은 JSONB 컬럼은 빈 목록·빈 객체로 저장된다 (NULL이 아님)
        assertThat(found.getSystemCandidates()).isEqualTo("[]");
        assertThat(found.getConfirmedFacts()).isEqualTo("[]");
        assertThat(found.getAssumptions()).isEqualTo("[]");
        assertThat(found.getMissingItems()).isEqualTo("[]");
        assertThat(found.getTypeDetail()).isEqualTo("{}");
        assertThat(found.getRelatedKnowledge()).isEqualTo("[]");

        // 같은 문제 + 같은 문의 버전 + 같은 AI 실행 → DB의 중복 불가 제약이 막는다
        InquiryProblem sameProblem = inquiryProblemRepository.findById(PROBLEM_ID).orElseThrow();
        AgentRun sameRun = agentRunRepository.findById(RUN_ID).orElseThrow();
        assertThatThrownBy(() -> standardRequestRepository.saveAndFlush(new StandardRequest(sameProblem, 1, sameRun,
                RequestIntent.UNKNOWN, WorkType.REVIEW_REQUIRED, null, null, null, "중복 스냅샷")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 같은_문의_버전이라도_새_AI_실행이면_새_스냅샷을_추가한다() {
        jdbcTemplate.update("INSERT INTO agent_run (id, inquiry_id, task, run_status, input_version, input_snapshot, "
                + "model_version, prompt_version, schema_version, started_at) "
                + "VALUES (?, ?, 'INQUIRY_ANALYSIS', 'RUNNING', 1, '{}', 'gpt-test', 'prompt-v1', 'schema-v1', now())",
                NEXT_RUN_ID, INQUIRY_ID);
        InquiryProblem problem = inquiryProblemRepository.findById(PROBLEM_ID).orElseThrow();
        standardRequestRepository.saveAndFlush(new StandardRequest(problem, 1,
                agentRunRepository.findById(RUN_ID).orElseThrow(), RequestIntent.UNKNOWN, WorkType.REVIEW_REQUIRED,
                null, null, null, "첫 분석"));

        StandardRequest reanalyzed = standardRequestRepository.saveAndFlush(new StandardRequest(problem, 1,
                agentRunRepository.findById(NEXT_RUN_ID).orElseThrow(), RequestIntent.OBSERVED_SYMPTOM,
                WorkType.INVESTIGATION, null, null, null, "재분석"));

        assertThat(reanalyzed.getId()).isNotNull();
        assertThat(standardRequestRepository.count()).isEqualTo(2);
    }
}
