package com.hihops.hops.knowledge.repository;

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

import com.hihops.hops.itsystem.domain.ItSystem;
import com.hihops.hops.itsystem.repository.ItSystemRepository;
import com.hihops.hops.knowledge.domain.AccessLevel;
import com.hihops.hops.knowledge.domain.CaseOrigin;
import com.hihops.hops.knowledge.domain.CaseStatus;
import com.hihops.hops.knowledge.domain.CaseType;
import com.hihops.hops.knowledge.domain.IndexStatus;
import com.hihops.hops.knowledge.domain.ResolvedCase;
import com.hihops.hops.ticket.domain.TicketWorkLog;
import com.hihops.hops.ticket.repository.TicketWorkLogRepository;

import jakarta.persistence.EntityManager;

/**
 * 해결 사례(resolved_case)를 저장하고, 처리 기록 하나에서 사례가 두 번 생기지 않는지 확인한다
 * (docs/ERD.md "해결 사례 resolved_case", 중복 불가: 원 처리 기록 ID).
 *
 * <p>Curator가 만든 사례는 초안(DRAFT)·Vector/Neo4j 반영 대기(PENDING)로 시작한다.
 * 승인되고 양쪽 반영이 모두 성공해야 검색에 쓴다.
 */
@SpringBootTest
@ActiveProfiles({"db", "test"})
@Transactional
class ResolvedCaseRepositoryTest {

    private static final UUID DEPARTMENT_ID = UUID.fromString("d0000000-0000-7000-8000-000000000099");
    private static final UUID MEMBER_ID = UUID.fromString("a0000000-0000-7000-8000-000000000099");
    private static final UUID SYSTEM_ID = UUID.fromString("c0000000-0000-7000-8000-000000000099");
    private static final UUID INQUIRY_ID = UUID.fromString("e0000000-0000-7000-8000-000000000099");
    private static final UUID PROBLEM_ID = UUID.fromString("e1000000-0000-7000-8000-000000000099");
    private static final UUID REQUEST_ID = UUID.fromString("e2000000-0000-7000-8000-000000000099");
    private static final UUID TICKET_ID = UUID.fromString("e3000000-0000-7000-8000-000000000099");
    private static final UUID WORK_LOG_ID = UUID.fromString("e4000000-0000-7000-8000-000000000099");

    @Autowired
    private ResolvedCaseRepository resolvedCaseRepository;

    @Autowired
    private TicketWorkLogRepository ticketWorkLogRepository;

    @Autowired
    private ItSystemRepository itSystemRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 테스트용_티켓과_해결_기록을_넣는다() {
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
        jdbcTemplate.update("INSERT INTO ticket_work_log (id, ticket_id, author_member_id, log_type, action_detail, "
                + "verification_result) VALUES (?, ?, ?, 'RESOLVED', '뷰어 캐시를 비움', '다시 열어 정상 표시 확인')",
                WORK_LOG_ID, TICKET_ID, MEMBER_ID);
    }

    @Test
    void 사례_초안을_저장하고_같은_처리_기록으로_사례를_두_번_만들지_않는다() {
        ResolvedCase saved = resolvedCaseRepository.save(newDraft("CASE-9999"));
        entityManager.flush();
        entityManager.clear();

        ResolvedCase found = resolvedCaseRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getCaseNumber()).isEqualTo("CASE-9999");
        assertThat(found.getSourceWorkLog().getId()).isEqualTo(WORK_LOG_ID);
        assertThat(found.getItSystem().getId()).isEqualTo(SYSTEM_ID);
        assertThat(found.getSystemFeature()).isNull();
        assertThat(found.getCaseType()).isEqualTo(CaseType.RESOLUTION);
        assertThat(found.getTitle()).isEqualTo("결재 문서 빈 화면");
        assertThat(found.getProblemStatement()).isEqualTo("결재함에서 문서를 열면 빈 화면이 보인다");
        assertThat(found.getActionDetail()).isEqualTo("뷰어 캐시를 비움");
        assertThat(found.getVerificationResult()).isEqualTo("다시 열어 정상 표시 확인");
        assertThat(found.getAccessLevel()).isEqualTo(AccessLevel.STAFF);
        assertThat(found.getOriginType()).isEqualTo(CaseOrigin.SYNTHETIC);
        // Curator 초안: 버전 1, 승인 전, 검색 반영 전, 목록 컬럼은 빈 목록
        assertThat(found.getVersionNo()).isEqualTo(1);
        assertThat(found.getCaseStatus()).isEqualTo(CaseStatus.DRAFT);
        assertThat(found.getVectorStatus()).isEqualTo(IndexStatus.PENDING);
        assertThat(found.getGraphStatus()).isEqualTo(IndexStatus.PENDING);
        assertThat(found.getConfirmedFacts()).isEqualTo("[]");
        assertThat(found.getDuplicateCandidates()).isEqualTo("[]");
        assertThat(found.getRelationCandidates()).isEqualTo("[]");
        assertThat(found.getApprovedBy()).isNull();
        assertThat(found.getApprovedAt()).isNull();

        // 같은 처리 기록으로 사례를 또 만들면 → DB의 중복 불가 제약이 막는다 (처리 기록 하나에서 사례는 최대 하나)
        assertThatThrownBy(() -> resolvedCaseRepository.saveAndFlush(newDraft("CASE-9998")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private ResolvedCase newDraft(String caseNumber) {
        TicketWorkLog workLog = ticketWorkLogRepository.findById(WORK_LOG_ID).orElseThrow();
        ItSystem system = itSystemRepository.findById(SYSTEM_ID).orElseThrow();
        return new ResolvedCase(caseNumber, workLog, system, null, CaseType.RESOLUTION, "결재 문서 빈 화면",
                "결재함에서 문서를 열면 빈 화면이 보인다", "뷰어 캐시를 비움", "다시 열어 정상 표시 확인",
                AccessLevel.STAFF, CaseOrigin.SYNTHETIC, null);
    }
}
