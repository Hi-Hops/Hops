package com.hihops.hops.knowledge.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.hihops.hops.knowledge.domain.ReadRule;

/**
 * 개발용 지식 문서 seed(R__seed_dev_knowledge.sql)를 실제로 적재해 정본 카탈로그(VIRTUAL_COMPANY.md 6절)와 같은지 확인한다.
 * 이 파일은 docs/knowledge/corpus에서 자동 생성된다. 원본과 SQL이 어긋났는지는 scripts/knowledge의 검사가 맡고,
 * 여기서는 생성된 SQL이 현재 스키마에 들어가고 공개 규칙(isCurrentlyVisible)에 맞게 동작하는지를 본다.
 */
@SpringBootTest
@ActiveProfiles({"db", "test"})
@Transactional
class KnowledgeSeedTest {

    private static final String EA_OWNER = "a0000000-0000-7000-8000-000000000011";
    private static final String LOGIN_OWNER = "a0000000-0000-7000-8000-000000000012";
    private static final String REQUESTER = "a0000000-0000-7000-8000-000000000101";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private KnowledgeDocumentVersionRepository versions;

    @Test
    void 문서_16건이_종류와_공개_범위별로_정본_카탈로그대로_들어간다() throws Exception {
        loadSeeds();

        assertThat(count("SELECT count(*) FROM knowledge_document")).isEqualTo(16);
        assertThat(count("SELECT count(*) FROM knowledge_document_version")).isEqualTo(16);
        assertThat(jdbc.queryForList("SELECT document_type || ':' || access_level || ':' || count(*) FROM knowledge_document "
                + "GROUP BY document_type, access_level ORDER BY 1", String.class))
                .containsExactly("EMPLOYEE_GUIDE:EMPLOYEE:8", "POLICY:EMPLOYEE:4", "STAFF_RUNBOOK:STAFF:4");
        assertThat(count("SELECT count(*) FROM knowledge_document WHERE data_split = 'RAG' AND review_status = 'APPROVED' "
                + "AND is_synthetic AND deleted_at IS NULL")).isEqualTo(16);
        assertThat(count("SELECT count(*) FROM knowledge_document_version WHERE version_no = 1 AND review_status = 'APPROVED'"))
                .isEqualTo(16);
    }

    @Test
    void 문서는_documentKey의_시스템에_연결된다() throws Exception {
        loadSeeds();

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT d.source_data ->> 'documentKey' AS document_key, s.code AS system_code "
                        + "FROM knowledge_document d LEFT JOIN it_system s ON s.id = d.it_system_id");
        assertThat(rows).hasSize(16);
        for (Map<String, Object> row : rows) {
            String key = (String) row.get("document_key");
            String expected = key.contains("-ea-") ? "ELECTRONIC_APPROVAL"
                    : key.contains("-login-") ? "COMMON_LOGIN" : "FILE_SHARING";
            assertThat(row.get("system_code")).as(key).isEqualTo(expected);
        }
    }

    @Test
    void content_digest는_본문의_UTF8_SHA256이고_front_matter는_본문에_없다() throws Exception {
        loadSeeds();

        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT content, content_digest FROM knowledge_document_version");
        assertThat(rows).hasSize(16);
        for (Map<String, Object> row : rows) {
            String content = (String) row.get("content");
            assertThat(HexFormat.of().formatHex(sha256.digest(content.getBytes(StandardCharsets.UTF_8))))
                    .isEqualTo(((String) row.get("content_digest")).trim());
            assertThat(content).doesNotStartWith("---").doesNotContain("\r").endsWith("\n");
            assertThat(content).startsWith("## ");
        }
    }

    @Test
    void 문서ID는_고정값이다() throws Exception {
        loadSeeds();

        // uuid5(NAMESPACE_URL, "hops-knowledge.example/guide-ea-approval-status"). 규칙이 바뀌면 이미 적재된 DB와 어긋난다.
        assertThat(jdbc.queryForObject("SELECT id FROM knowledge_document WHERE source_data ->> 'documentKey' = 'guide-ea-approval-status'",
                UUID.class)).isEqualTo(UUID.fromString("bc670240-53a6-54a5-9a07-ca01e362be69"));
    }

    @Test
    void 직원_문서는_누구나_보고_담당자_런북은_해당_시스템_주담당자만_본다() throws Exception {
        loadSeeds();

        UUID guide = versionId("guide-ea-document-search");
        UUID runbook = versionId("runbook-ea-document-not-listed");
        UUID loginRunbook = versionId("runbook-login-sso-loop");

        assertThat(versions.isCurrentlyVisible(guide, UUID.fromString(REQUESTER), ReadRule.user(false))).isTrue();
        assertThat(versions.isCurrentlyVisible(guide, UUID.fromString(LOGIN_OWNER), ReadRule.user(false))).isTrue();

        assertThat(versions.isCurrentlyVisible(runbook, UUID.fromString(EA_OWNER), ReadRule.user(false))).isTrue();
        assertThat(versions.isCurrentlyVisible(runbook, UUID.fromString(LOGIN_OWNER), ReadRule.user(false))).isFalse();
        assertThat(versions.isCurrentlyVisible(runbook, UUID.fromString(REQUESTER), ReadRule.user(false))).isFalse();

        assertThat(versions.isCurrentlyVisible(loginRunbook, UUID.fromString(LOGIN_OWNER), ReadRule.user(false))).isTrue();
        assertThat(versions.isCurrentlyVisible(loginRunbook, UUID.fromString(EA_OWNER), ReadRule.user(false))).isFalse();
    }

    @Test
    void 두_번_적재해도_행이_늘지_않는다() throws Exception {
        loadSeeds();
        loadSeeds();

        assertThat(count("SELECT count(*) FROM knowledge_document")).isEqualTo(16);
        assertThat(count("SELECT count(*) FROM knowledge_document_version")).isEqualTo(16);
    }

    private UUID versionId(String documentKey) {
        return jdbc.queryForObject("SELECT v.id FROM knowledge_document_version v JOIN knowledge_document d "
                + "ON d.id = v.knowledge_document_id WHERE d.source_data ->> 'documentKey' = ? AND v.version_no = 1",
                UUID.class, documentKey);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private void loadSeeds() throws Exception {
        // R__ 파일은 이름 순으로 실행된다: department_member → it_system → knowledge.
        // 본문은 $kb$ 인용 안에 세미콜론을 포함하므로 문장을 나누지 않고 서버에 통째로 보낸다(Flyway도 같은 인용을 인식한다).
        for (String file : List.of("R__seed_dev_department_member.sql", "R__seed_dev_it_system.sql", "R__seed_dev_knowledge.sql")) {
            jdbc.execute(new ClassPathResource("db/seed/" + file).getContentAsString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
        }
    }
}
