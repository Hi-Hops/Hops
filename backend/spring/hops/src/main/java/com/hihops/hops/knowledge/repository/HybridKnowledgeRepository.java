package com.hihops.hops.knowledge.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.hihops.hops.knowledge.service.KnowledgeSearchService.Query;
import com.hihops.hops.knowledge.service.QueryEmbedding;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class HybridKnowledgeRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public record RankedChunk(UUID versionId, String content, String digest, String chunkDigest, double score,
            Integer keywordRank, Integer semanticRank, Double keywordScore, Double semanticSimilarity,
            String keywordContent) {}

    /** 두 채널 모두 청크를 먼저 점수화하고 문서 버전당 가장 높은 청크 하나를 남긴다. */
    public List<RankedChunk> rank(Query query, List<String> tokens, List<String> codes, String vector,
            boolean requireIndexed, String mode, double minSimilarity) {
        if (query.accessLevels().isEmpty() || query.documentTypes().isEmpty()) {
            return List.of();
        }
        String systemCondition = query.systemIds().isEmpty()
                ? "d.it_system_id IS NULL AND d.document_type = 'POLICY'"
                : "(d.it_system_id IS NULL AND d.document_type = 'POLICY') OR (s.deleted_at IS NULL AND s.id IN (:systems))";
        String sql = """
                WITH eligible AS MATERIALIZED (
                    SELECT v.id AS version_id, c.chunk_no, c.content, c.content_digest, c.chunk_digest,
                           lower(normalize(d.title)) AS title, lower(normalize(c.content)) AS body,
                           c.embedding, c.embedding_model
                    FROM hops_knowledge.knowledge_chunk c
                    JOIN knowledge_document_version v ON v.id = c.knowledge_document_version_id
                    JOIN knowledge_document d ON d.id = v.knowledge_document_id
                    LEFT JOIN it_system s ON s.id = d.it_system_id
                    WHERE c.content_digest = v.content_digest
                      AND d.deleted_at IS NULL AND d.review_status = 'APPROVED' AND v.review_status = 'APPROVED'
                      AND d.data_split = 'RAG' AND d.access_level IN (:levels) AND d.document_type IN (:types)
                      AND (%s)
                      AND (d.access_level = 'EMPLOYEE' OR :internalRead OR s.primary_owner_member_id = :member
                           OR (d.it_system_id IS NULL AND EXISTS (SELECT 1 FROM it_system owned
                               WHERE owned.deleted_at IS NULL AND owned.primary_owner_member_id = :member)))
                      AND v.version_no = (SELECT max(v2.version_no) FROM knowledge_document_version v2
                          WHERE v2.knowledge_document_id = d.id AND v2.review_status = 'APPROVED')
                      AND (d.document_type <> 'CASE' OR EXISTS (SELECT 1 FROM resolved_case rc
                          JOIN ticket_work_log w ON w.id = rc.source_work_log_id
                          JOIN ticket t ON t.id = w.ticket_id
                          WHERE CAST(rc.id AS text) = v.source_metadata ->> 'caseId'
                            AND CAST(rc.version_no AS text) = v.source_metadata ->> 'caseVersion'
                            AND rc.case_status = 'APPROVED' AND rc.deleted_at IS NULL
                            AND rc.approved_at IS NOT NULL AND rc.approved_by_member_id IS NOT NULL
                            AND rc.it_system_id = d.it_system_id AND rc.access_level = d.access_level
                            AND t.deleted_at IS NULL AND t.ticket_status = 'RESOLVED' AND t.resolved_at IS NOT NULL
                            AND t.responsible_system_id = rc.it_system_id AND w.log_type IN ('RESOLVED', 'IMPLEMENTED')
                            AND (NOT :requireIndexed OR (rc.vector_status = 'SUCCEEDED' AND rc.graph_status = 'SUCCEEDED'))))
                ),
                keyword AS (
                    SELECT e.*, score.raw + exact.hits * 8 AS score
                    FROM eligible e
                    CROSS JOIN LATERAL (
                        SELECT count(*) FILTER (WHERE in_title OR in_body) AS matched,
                               coalesce(sum(CASE WHEN in_title THEN 3 ELSE 0 END + CASE WHEN in_body THEN 1 ELSE 0 END), 0) AS raw
                        FROM (SELECT strpos(title, token) > 0 AS in_title, strpos(body, token) > 0 AS in_body
                              FROM unnest(ARRAY[:tokens]::text[]) token WHERE token <> '') matches
                    ) score
                    CROSS JOIN LATERAL (
                        SELECT count(*) AS hits FROM unnest(ARRAY[:codes]::text[]) code
                        WHERE code <> '' AND (strpos(title, code) > 0 OR strpos(body, code) > 0)
                    ) exact
                    WHERE :mode <> 'vector' AND (score.matched >= :required OR exact.hits > 0)
                ),
                semantic AS (
                    SELECT e.*, 1 - (embedding <=> CAST(:vector AS vector)) AS score
                    FROM eligible e WHERE :mode <> 'keyword' AND embedding_model = :model
                ),
                keyword_best AS (
                    SELECT DISTINCT ON (version_id) * FROM keyword ORDER BY version_id, score DESC, chunk_no
                ),
                semantic_best AS (
                    SELECT DISTINCT ON (version_id) * FROM semantic WHERE score >= :minSimilarity
                    ORDER BY version_id, score DESC, chunk_no
                ),
                k AS (
                    SELECT *, row_number() OVER (ORDER BY score DESC, version_id) AS rank
                    FROM keyword_best ORDER BY score DESC, version_id LIMIT 20
                ),
                v AS (
                    SELECT *, row_number() OVER (ORDER BY score DESC, version_id) AS rank
                    FROM semantic_best ORDER BY score DESC, version_id LIMIT 20
                )
                SELECT coalesce(k.version_id, v.version_id) AS version_id, coalesce(k.content, v.content) AS content,
                       coalesce(k.content_digest, v.content_digest) AS content_digest,
                       coalesce(k.chunk_digest, v.chunk_digest) AS chunk_digest,
                       coalesce(0.8 / (10 + k.rank), 0) + coalesce(1.0 / (10 + v.rank), 0) AS score,
                       CAST(k.rank AS integer) AS keyword_rank, CAST(v.rank AS integer) AS semantic_rank,
                       CAST(k.score AS double precision) AS keyword_score,
                       v.score AS semantic_similarity, k.content AS keyword_content
                FROM k FULL JOIN v ON k.version_id = v.version_id
                ORDER BY score DESC, k.rank NULLS LAST, v.rank NULLS LAST
                """.formatted(systemCondition);
        var params = new MapSqlParameterSource()
                .addValue("systems", query.systemIds())
                .addValue("levels", query.accessLevels().stream().map(Enum::name).toList())
                .addValue("types", query.documentTypes().stream().map(Enum::name).toList())
                .addValue("member", query.memberId())
                .addValue("internalRead", query.internalRead())
                .addValue("requireIndexed", requireIndexed)
                .addValue("model", QueryEmbedding.MODEL)
                .addValue("tokens", tokens.isEmpty() ? List.of("") : tokens)
                .addValue("codes", codes.isEmpty() ? List.of("") : codes)
                .addValue("required", tokens.size() >= 3 ? 2 : 1)
                .addValue("mode", mode)
                .addValue("minSimilarity", minSimilarity)
                .addValue("vector", vector, java.sql.Types.VARCHAR);
        return jdbc.query(sql, params, (rs, row) -> new RankedChunk(rs.getObject("version_id", UUID.class),
                rs.getString("content"), rs.getString("content_digest"), rs.getString("chunk_digest"),
                rs.getDouble("score"), rs.getObject("keyword_rank", Integer.class),
                rs.getObject("semantic_rank", Integer.class), rs.getObject("keyword_score", Double.class),
                rs.getObject("semantic_similarity", Double.class), rs.getString("keyword_content")));
    }
}
