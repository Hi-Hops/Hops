package com.hihops.hops.inquiry.repository;

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
import com.hihops.hops.inquiry.domain.InquiryStatus;
import com.hihops.hops.user.domain.Member;
import com.hihops.hops.user.repository.MemberRepository;

import jakarta.persistence.EntityManager;

/**
 * 문의(inquiry)를 문의자와 함께 저장하고 다시 읽는지 확인한다 (docs/ERD.md "문의 inquiry").
 *
 * <p>문의 버전(version)은 1부터 시작해야 한다. 메시지 추가·정정 때마다 늘려서 늦게 도착한 예전 AI 결과를 버리는 기준이다.
 */
@SpringBootTest
@ActiveProfiles({"db", "test"})
@Transactional
class InquiryRepositoryTest {

    private static final UUID DEPARTMENT_ID = UUID.fromString("d0000000-0000-7000-8000-000000000099");
    private static final UUID REQUESTER_ID = UUID.fromString("a0000000-0000-7000-8000-000000000099");

    @Autowired
    private InquiryRepository inquiryRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 테스트용_부서와_문의자를_넣는다() {
        jdbcTemplate.update("INSERT INTO department (id, code, name) VALUES (?, 'TEST_SALES', '테스트 영업팀')", DEPARTMENT_ID);
        jdbcTemplate.update("INSERT INTO member (id, login_id, employee_no, password_hash, name, department_id) "
                + "VALUES (?, 'test.requester', '29990099', 'hash', '테스트 문의자', ?)", REQUESTER_ID, DEPARTMENT_ID);
    }

    @Test
    void 문의를_저장하면_버전_1로_시작하고_DB에서_다시_읽는다() {
        Member requester = memberRepository.findById(REQUESTER_ID).orElseThrow();
        Inquiry saved = inquiryRepository.save(
                new Inquiry("Q-9999", requester, "결재 문서가 안 열려요", InquiryStatus.IN_PROGRESS));
        entityManager.flush();
        entityManager.clear();

        Inquiry found = inquiryRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getInquiryNumber()).isEqualTo("Q-9999");
        assertThat(found.getRequester().getId()).isEqualTo(REQUESTER_ID);
        assertThat(found.getTitle()).isEqualTo("결재 문서가 안 열려요");
        assertThat(found.getInquiryStatus()).isEqualTo(InquiryStatus.IN_PROGRESS);
        assertThat(found.getVersion()).isEqualTo(1);
        // 아직 AI 결과를 적용하지 않아 결과 차수는 1이고 분석 중인 실행도 없다
        assertThat(found.getResultRevision()).isEqualTo(1);
        assertThat(found.getCurrentRun()).isNull();
        // AI 요약은 아직 만들어지지 않았다
        assertThat(found.getSummary()).isNull();
        assertThat(found.getSummaryUntilSeq()).isNull();
        assertThat(found.getSummaryUpdatedAt()).isNull();
    }
}
