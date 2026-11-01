package com.hihops.hops.knowledge.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.hihops.hops.itsystem.domain.ItSystem;
import com.hihops.hops.itsystem.repository.ItSystemRepository;
import com.hihops.hops.knowledge.domain.AccessLevel;
import com.hihops.hops.knowledge.domain.DataSplit;
import com.hihops.hops.knowledge.domain.DocumentType;
import com.hihops.hops.knowledge.domain.KnowledgeDocument;
import com.hihops.hops.knowledge.domain.ReviewStatus;

import jakarta.persistence.EntityManager;

/**
 * 지식 문서(knowledge_document)를 저장하고 다시 읽는지 확인한다 (docs/ERD.md "지식 문서 knowledge_document").
 *
 * <p>새 문서는 검수 대기(PENDING)·가상 데이터(isSynthetic=true)로 시작한다. 검수 승인(APPROVED)된 문서만 검색에 쓴다.
 */
@SpringBootTest
@ActiveProfiles({"db", "test"})
@Transactional
class KnowledgeDocumentRepositoryTest {

    private static final UUID DEPARTMENT_ID = UUID.fromString("d0000000-0000-7000-8000-000000000099");
    private static final UUID MEMBER_ID = UUID.fromString("a0000000-0000-7000-8000-000000000099");
    private static final UUID SYSTEM_ID = UUID.fromString("c0000000-0000-7000-8000-000000000099");

    @Autowired
    private KnowledgeDocumentRepository knowledgeDocumentRepository;

    @Autowired
    private ItSystemRepository itSystemRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 테스트용_시스템을_넣는다() {
        jdbcTemplate.update("INSERT INTO department (id, code, name) VALUES (?, 'TEST_IT', '테스트 IT팀')", DEPARTMENT_ID);
        jdbcTemplate.update("INSERT INTO member (id, login_id, employee_no, password_hash, name, department_id) "
                + "VALUES (?, 'test.owner', '29990099', 'hash', '테스트 주담당자', ?)", MEMBER_ID, DEPARTMENT_ID);
        jdbcTemplate.update("INSERT INTO it_system (id, code, name, primary_owner_member_id) "
                + "VALUES (?, 'TEST_APPROVAL', '테스트 전자결재', ?)", SYSTEM_ID, MEMBER_ID);
    }

    @Test
    void 직원_가이드를_저장하면_검수_대기_가상_데이터로_시작한다() {
        ItSystem system = itSystemRepository.findById(SYSTEM_ID).orElseThrow();
        KnowledgeDocument saved = knowledgeDocumentRepository.save(new KnowledgeDocument("결재 문서 열람 가이드",
                DocumentType.EMPLOYEE_GUIDE, system, AccessLevel.EMPLOYEE, "{\"dataset\": \"sample\"}", DataSplit.RAG));
        entityManager.flush();
        entityManager.clear();

        KnowledgeDocument found = knowledgeDocumentRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getTitle()).isEqualTo("결재 문서 열람 가이드");
        assertThat(found.getDocumentType()).isEqualTo(DocumentType.EMPLOYEE_GUIDE);
        assertThat(found.getItSystem().getId()).isEqualTo(SYSTEM_ID);
        assertThat(found.getAccessLevel()).isEqualTo(AccessLevel.EMPLOYEE);
        assertThat(found.getSourceData()).isEqualToIgnoringWhitespace("{\"dataset\": \"sample\"}");
        assertThat(found.getDataSplit()).isEqualTo(DataSplit.RAG);
        // 사람이 검수하기 전이고, 가상 데이터로 표시된다
        assertThat(found.getReviewStatus()).isEqualTo(ReviewStatus.PENDING);
        assertThat(found.isSynthetic()).isTrue();
    }
}
