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

import com.hihops.hops.knowledge.domain.KnowledgeDocument;
import com.hihops.hops.knowledge.domain.KnowledgeDocumentVersion;

import jakarta.persistence.EntityManager;

/**
 * 문서 버전(knowledge_document_version)을 저장하고, 같은 문서에 같은 버전 번호가 두 번 들어가지 않는지 확인한다
 * (docs/ERD.md "문서 버전 knowledge_document_version", 중복 불가 조합: 지식 문서 ID + 버전 번호).
 */
@SpringBootTest
@ActiveProfiles({"db", "test"})
@Transactional
class KnowledgeDocumentVersionRepositoryTest {

    private static final UUID DOCUMENT_ID = UUID.fromString("f1000000-0000-7000-8000-000000000099");

    @Autowired
    private KnowledgeDocumentVersionRepository knowledgeDocumentVersionRepository;

    @Autowired
    private KnowledgeDocumentRepository knowledgeDocumentRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 테스트용_공통_정책_문서를_넣는다() {
        // 공통 정책이라 시스템 없이 넣는다
        jdbcTemplate.update("INSERT INTO knowledge_document (id, title, document_type, access_level, data_split) "
                + "VALUES (?, '비밀번호 정책', 'POLICY', 'EMPLOYEE', 'RAG')", DOCUMENT_ID);
    }

    @Test
    void 문서_버전을_저장하고_같은_문서에_같은_버전_번호는_막는다() {
        KnowledgeDocument document = knowledgeDocumentRepository.findById(DOCUMENT_ID).orElseThrow();
        KnowledgeDocumentVersion saved = knowledgeDocumentVersionRepository.save(
                new KnowledgeDocumentVersion(document, 1, "비밀번호는 90일마다 바꾼다."));
        entityManager.flush();
        entityManager.clear();

        KnowledgeDocumentVersion found = knowledgeDocumentVersionRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getKnowledgeDocument().getId()).isEqualTo(DOCUMENT_ID);
        assertThat(found.getVersionNo()).isEqualTo(1);
        assertThat(found.getContent()).isEqualTo("비밀번호는 90일마다 바꾼다.");
        assertThat(found.getCreatedAt()).isNotNull();

        // 같은 문서 + 같은 버전 번호 → DB의 중복 불가 제약이 막는다 (내용을 고치면 새 버전 번호로 추가한다)
        KnowledgeDocument sameDocument = knowledgeDocumentRepository.findById(DOCUMENT_ID).orElseThrow();
        assertThatThrownBy(() -> knowledgeDocumentVersionRepository.saveAndFlush(
                new KnowledgeDocumentVersion(sameDocument, 1, "덮어쓰기 시도")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
