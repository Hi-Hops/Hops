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

import com.hihops.hops.knowledge.domain.KnowledgeChunk;
import com.hihops.hops.knowledge.domain.KnowledgeDocumentVersion;

import jakarta.persistence.EntityManager;

/**
 * 검색 조각(knowledge_chunk)을 임베딩 벡터와 함께 저장하고, 같은 문서 버전에 같은 조각 순번이 두 번 들어가지 않는지 확인한다
 * (docs/ERD.md "검색 조각 knowledge_chunk", 중복 불가 조합: 문서 버전 ID + 조각 순번).
 *
 * <p>embedding은 pgvector의 vector 타입이다. ERD에 차원 수가 정해지지 않아 짧은 3차원 벡터로 저장·재조회를 확인한다.
 * 로컬 DB에 pgvector 확장이 켜져 있어야 한다 (Spring README "pgvector").
 */
@SpringBootTest
@ActiveProfiles({"db", "test"})
@Transactional
class KnowledgeChunkRepositoryTest {

    private static final UUID DOCUMENT_ID = UUID.fromString("f1000000-0000-7000-8000-000000000099");
    private static final UUID VERSION_ID = UUID.fromString("f2000000-0000-7000-8000-000000000099");

    @Autowired
    private KnowledgeChunkRepository knowledgeChunkRepository;

    @Autowired
    private KnowledgeDocumentVersionRepository knowledgeDocumentVersionRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 테스트용_문서와_문서_버전을_넣는다() {
        jdbcTemplate.update("INSERT INTO knowledge_document (id, title, document_type, access_level, data_split) "
                + "VALUES (?, '비밀번호 정책', 'POLICY', 'EMPLOYEE', 'RAG')", DOCUMENT_ID);
        jdbcTemplate.update("INSERT INTO knowledge_document_version (id, knowledge_document_id, version_no, content) "
                + "VALUES (?, ?, 1, '비밀번호는 90일마다 바꾼다. 최근 3개는 다시 쓸 수 없다.')", VERSION_ID, DOCUMENT_ID);
    }

    @Test
    void 조각을_임베딩과_함께_저장하고_같은_버전에_같은_순번은_막는다() {
        KnowledgeDocumentVersion version = knowledgeDocumentVersionRepository.findById(VERSION_ID).orElseThrow();
        KnowledgeChunk saved = knowledgeChunkRepository.save(
                new KnowledgeChunk(version, 1, "비밀번호는 90일마다 바꾼다.", new float[] {0.1f, -0.2f, 0.3f}));
        entityManager.flush();
        entityManager.clear();

        KnowledgeChunk found = knowledgeChunkRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getKnowledgeDocumentVersion().getId()).isEqualTo(VERSION_ID);
        assertThat(found.getChunkNo()).isEqualTo(1);
        assertThat(found.getContent()).isEqualTo("비밀번호는 90일마다 바꾼다.");
        assertThat(found.getEmbedding()).containsExactly(0.1f, -0.2f, 0.3f);
        assertThat(found.getCreatedAt()).isNotNull();

        // 같은 문서 버전 + 같은 조각 순번 → DB의 중복 불가 제약이 막는다
        KnowledgeDocumentVersion sameVersion = knowledgeDocumentVersionRepository.findById(VERSION_ID).orElseThrow();
        assertThatThrownBy(() -> knowledgeChunkRepository.saveAndFlush(
                new KnowledgeChunk(sameVersion, 1, "중복 순번", new float[] {0f, 0f, 0f})))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
