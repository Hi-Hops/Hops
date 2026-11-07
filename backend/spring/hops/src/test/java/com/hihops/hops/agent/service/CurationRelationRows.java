package com.hihops.hops.agent.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/** 사례 연결 테스트용 관계 노드·근거 문서 픽스처. 만든 행은 {@link #clear}로 지운다. */
final class CurationRelationRows {
    final UUID document = UUID.randomUUID();
    final UUID version;
    final UUID feature = UUID.randomUUID();
    private final JdbcTemplate jdbc;

    CurationRelationRows(JdbcTemplate jdbc, UUID systemId) {
        this.jdbc = jdbc;
        String content = "가상 근거 문서 " + document;
        jdbc.update("INSERT INTO knowledge_document(id,title,document_type,it_system_id,access_level,data_split,review_status) VALUES (?,'가상 근거','STAFF_RUNBOOK',?,'STAFF','RAG','APPROVED')", document, systemId);
        jdbc.update("INSERT INTO knowledge_document_version(knowledge_document_id,version_no,content,content_digest,source_metadata,review_status) VALUES (?,1,?,?,?::jsonb,'APPROVED')",
                document, content, digest(content), "{\"origin\":\"SYNTHETIC\",\"applicability\":\"가상\",\"appliesToSystemVersion\":null}");
        this.version = jdbc.queryForObject("SELECT id FROM knowledge_document_version WHERE knowledge_document_id=?", UUID.class, document);
        jdbc.update("INSERT INTO system_feature(id,it_system_id,code,name) VALUES (?,?,?,'문서 조회')", feature, systemId, "F" + feature.toString().substring(0, 8));
    }

    UUID symptom(UUID systemId, String key, String label, String level, String review) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO relation_symptom(id,catalog_key,label,it_system_id,system_feature_id,access_level,review_status,source_document_version_id) VALUES (?,?,?,?,?,?,?,?)",
                id, key + "-" + id.toString().substring(0, 8), label, systemId, feature, level, review, version);
        return id;
    }

    UUID check(UUID systemId, String key, String label, boolean disclosable, String level, String review) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO relation_check_item(id,catalog_key,label,it_system_id,employee_disclosable,access_level,review_status,source_document_version_id) VALUES (?,?,?,?,?,?,?,?)",
                id, key + "-" + id.toString().substring(0, 8), label, systemId, disclosable, level, review, version);
        return id;
    }

    void clear() {
        jdbc.update("DELETE FROM relation_check_item WHERE source_document_version_id=?", version);
        jdbc.update("DELETE FROM relation_symptom WHERE source_document_version_id=?", version);
        jdbc.update("DELETE FROM system_feature WHERE id=?", feature);
        jdbc.update("DELETE FROM knowledge_document_version WHERE knowledge_document_id=?", document);
        jdbc.update("DELETE FROM knowledge_document WHERE id=?", document);
    }

    private static String digest(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
