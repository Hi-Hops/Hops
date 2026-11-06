package com.hihops.hops.knowledge.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * 지식 검색의 후보 문서 버전을 SQL로 찾는다(1차). 공개 범위·시스템·종류·승인 상태는 이 조회의 조건이고,
 * 현재 권한·CASE 공개 규칙의 최종 판정은 반환 전에 {@code isCurrentlyVisible}로 다시 한다(SEC-09).
 * 같은 문서의 승인된 최신 버전만 후보다.
 */
@Repository
@RequiredArgsConstructor
public class KnowledgeSearchRepository {
    /** 후보 상한. 시드가 작아 충분하며 상한을 넘으면 문서 ID 순으로 잘린다. */
    public static final int MAX_CANDIDATES = 200;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * @param patterns 소문자 LIKE 패턴(%토큰%). 제목 또는 원문에 하나라도 맞으면 후보다. 비어 있으면 후보가 없다
     */
    public List<UUID> findCandidateVersionIds(Collection<String> accessLevels, Collection<String> documentTypes,
            Collection<UUID> systemIds, Collection<String> patterns) {
        if (accessLevels.isEmpty() || documentTypes.isEmpty() || patterns.isEmpty()) {
            return List.of();
        }
        // 공통 정책(시스템 없음)은 POLICY만 허용한다. 접근 가능한 시스템이 없으면 시스템 문서는 없다.
        String systemCondition = systemIds.isEmpty()
                ? "(d.it_system_id IS NULL AND d.document_type = 'POLICY')"
                : "((d.it_system_id IS NULL AND d.document_type = 'POLICY') OR (s.id IS NOT NULL AND s.deleted_at IS NULL AND s.id IN (:systemIds)))";
        String sql = """
                SELECT v.id FROM knowledge_document_version v
                JOIN knowledge_document d ON d.id = v.knowledge_document_id
                LEFT JOIN it_system s ON s.id = d.it_system_id
                WHERE d.deleted_at IS NULL AND d.review_status = 'APPROVED' AND v.review_status = 'APPROVED'
                  AND d.data_split = 'RAG'
                  AND d.access_level IN (:accessLevels) AND d.document_type IN (:documentTypes)
                  AND v.version_no = (SELECT max(v2.version_no) FROM knowledge_document_version v2
                                      WHERE v2.knowledge_document_id = d.id AND v2.review_status = 'APPROVED')
                  AND %s
                  AND (lower(d.title) LIKE ANY (ARRAY[:patterns]::text[]) OR lower(v.content) LIKE ANY (ARRAY[:patterns]::text[]))
                ORDER BY d.id, v.version_no
                LIMIT %d
                """.formatted(systemCondition, MAX_CANDIDATES);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("accessLevels", accessLevels)
                .addValue("documentTypes", documentTypes)
                .addValue("patterns", patterns);
        if (!systemIds.isEmpty()) {
            params.addValue("systemIds", systemIds);
        }
        return jdbc.query(sql, params, (rs, row) -> rs.getObject(1, UUID.class));
    }
}
