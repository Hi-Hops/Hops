package com.hihops.hops.relation.repository;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.hihops.hops.relation.repository.RelationSearchRepository.CaseLinkRow;
import com.hihops.hops.relation.repository.RelationSearchRepository.CauseCheckRow;
import com.hihops.hops.relation.repository.RelationSearchRepository.CauseRow;
import com.hihops.hops.relation.repository.RelationSearchRepository.CheckRow;
import com.hihops.hops.relation.repository.RelationSearchRepository.QuestionRow;
import com.hihops.hops.relation.repository.RelationSearchRepository.SymptomRow;
import com.hihops.hops.relation.repository.RelationSearchRepository.Viewer;
import com.hihops.hops.relation.service.RelationSearchService.Query;

import tools.jackson.databind.JsonNode;

/** Neo4j 5의 HTTP 트랜잭션 API. PostgreSQL에서는 관계 대신 현재 시스템·기능·문서 승인 문맥만 읽는다. */
@Repository
public class Neo4jRelationReader {
    private static final String ACTIVE_SYMPTOMS = """
            MATCH (sys:HopsP0:System)-[:HAS_FEATURE]->(f:HopsP0:Feature)-[:HAS_SYMPTOM]->(s:HopsP0:Symptom)
            WHERE $features[f.id] = sys.id AND s.it_system_id = sys.id
              AND s.review_status = 'APPROVED' AND $sources[s.source_document_version_id] = sys.id
              AND s.access_level IN $levels AND (s.access_level = 'EMPLOYEE' OR sys.id IN $staffSystems)
              AND (f.id IN $featureIds OR any(token IN $tokens WHERE toLower(s.label) CONTAINS token))
            """;

    private static final String CHECK_PATH = """
            MATCH (s)-[sc:MAY_HAVE_CAUSE]->(c:HopsP0:Cause)-[cc:CHECKED_BY]->(k:HopsP0:CheckItem)
            WHERE all(item IN [sc, c, cc, k] WHERE item.review_status = 'APPROVED'
                      AND $sources[item.source_document_version_id] = sys.id)
              AND c.it_system_id = sys.id AND k.it_system_id = sys.id
              AND (k.employee_disclosable OR ($staff AND k.access_level IN $levels
                   AND (k.access_level = 'EMPLOYEE' OR sys.id IN $staffSystems)))
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final RestClient client;
    private final String password;
    private final String database;

    public Neo4jRelationReader(NamedParameterJdbcTemplate jdbc,
            @Value("${hops.search.neo4j-url:http://localhost:7474}") String url,
            @Value("${hops.neo4j.username:neo4j}") String username,
            @Value("${hops.neo4j.password:}") String password,
            @Value("${hops.search.neo4j-database:neo4j}") String database) {
        this.jdbc = jdbc;
        this.password = password;
        this.database = database;
        var http = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(1)).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(3));
        client = RestClient.builder().requestFactory(factory).baseUrl(url)
                .defaultHeaders(headers -> headers.setBasicAuth(username, password)).build();
    }

    public record Rows(List<SymptomRow> symptoms, List<CheckRow> checks, List<QuestionRow> questions,
            List<CauseRow> causes, List<CauseCheckRow> causeChecks, List<CaseLinkRow> cases) {}

    public Map<UUID, UUID> featureSystems(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, String> current = currentFeatures(null, ids);
        return query(Map.of("features", current), List.of("""
                MATCH (sys:HopsP0:System)-[:HAS_FEATURE]->(f:HopsP0:Feature)
                WHERE $features[f.id] = sys.id RETURN f.id, sys.id
                """)).getFirst().stream().collect(Collectors.toMap(row -> id(row, 0), row -> id(row, 1)));
    }

    public Rows search(Query query) {
        var params = parameters(query.systemIds(), new Viewer(query.memberId(), query.internalRead()));
        params.addValue("levels", query.levels().stream().map(Enum::name).toList())
                .addValue("staff", query.includesStaff())
                .addValue("tokens", query.tokens())
                .addValue("featureIds", strings(query.featureIds()));
        List<List<JsonNode>> results = query(params.getValues(), List.of(
                ACTIVE_SYMPTOMS + """
                RETURN s.id, s.catalog_key, s.label, sys.id, f.id, f.code, s.access_level, s.source_document_version_id
                """,
                ACTIVE_SYMPTOMS + CHECK_PATH + """
                RETURN s.id, k.id, k.catalog_key,
                       CASE WHEN $staff AND k.access_level IN $levels
                             AND (k.access_level = 'EMPLOYEE' OR sys.id IN $staffSystems)
                            THEN k.label ELSE null END AS label,
                       k.employee_disclosable, k.access_level, k.source_document_version_id,
                       count(DISTINCT c) AS causeCount
                ORDER BY s.id, causeCount DESC, k.catalog_key
                """,
                ACTIVE_SYMPTOMS + CHECK_PATH + """
                MATCH (k)-[:HAS_QUESTION]->(q:HopsP0:Question)
                WHERE q.review_status = 'APPROVED' AND $sources[q.source_document_version_id] = sys.id
                  AND ((q.employee_disclosable AND k.employee_disclosable AND q.access_level = 'EMPLOYEE')
                       OR ($staff AND q.access_level IN $levels
                           AND (q.access_level = 'EMPLOYEE' OR sys.id IN $staffSystems)))
                RETURN DISTINCT k.id, q.id, q.catalog_key, q.question_text, q.access_level,
                       q.source_document_version_id, q.employee_disclosable
                ORDER BY k.id, q.catalog_key
                """,
                ACTIVE_SYMPTOMS + """
                MATCH (s)-[sc:MAY_HAVE_CAUSE]->(c:HopsP0:Cause)
                WHERE $staff AND c.it_system_id = sys.id
                  AND all(item IN [sc, c] WHERE item.review_status = 'APPROVED'
                      AND $sources[item.source_document_version_id] = sys.id
                      AND item.access_level IN $levels
                      AND (item.access_level = 'EMPLOYEE' OR sys.id IN $staffSystems))
                OPTIONAL MATCH (c)-[cc:CHECKED_BY]->(k:HopsP0:CheckItem)
                WHERE cc.review_status = 'APPROVED' AND $sources[cc.source_document_version_id] = sys.id
                  AND k.review_status = 'APPROVED' AND $sources[k.source_document_version_id] = sys.id
                  AND k.it_system_id = sys.id
                  AND (k.employee_disclosable OR (k.access_level IN $levels
                      AND (k.access_level = 'EMPLOYEE' OR sys.id IN $staffSystems)))
                RETURN s.id, c.id, c.catalog_key, c.label, c.access_level, c.source_document_version_id, collect(DISTINCT k.id)
                ORDER BY s.id, c.catalog_key
                """,
                ACTIVE_SYMPTOMS + """
                MATCH (c:HopsP0:ApprovedCase)-[a:ADDRESSES]->(s)
                WHERE c.it_system_id = sys.id AND c.review_status = 'APPROVED'
                  AND $sources[c.source_document_version_id] = sys.id
                  AND a.review_status = 'APPROVED' AND a.source_document_version_id = c.source_document_version_id
                  AND all(item IN [a, c] WHERE item.access_level IN $levels
                          AND (item.access_level = 'EMPLOYEE' OR sys.id IN $staffSystems))
                RETURN s.id, c.id, c.version_no, c.case_number ORDER BY s.id, c.case_number
                """));
        var symptoms = results.get(0).stream().map(row -> new SymptomRow(id(row, 0), value(row, 1), value(row, 2),
                id(row, 3), id(row, 4), value(row, 5), value(row, 6), id(row, 7))).toList();
        var checks = results.get(1).stream().map(row -> new CheckRow(id(row, 0), id(row, 1), value(row, 2),
                row.get(3).isNull() ? null : value(row, 3), row.get(4).asBoolean(), value(row, 5), id(row, 6), row.get(7).asInt())).toList();
        var questions = results.get(2).stream().map(row -> new QuestionRow(id(row, 0), id(row, 1),
                value(row, 2), value(row, 3), value(row, 4), id(row, 5), row.get(6).asBoolean())).toList();
        var causes = results.get(3).stream().map(row -> new CauseRow(id(row, 0), id(row, 1), value(row, 2),
                value(row, 3), value(row, 4), id(row, 5))).toList();
        var causeChecks = results.get(3).stream().flatMap(row -> row.get(6).valueStream()
                .map(check -> new CauseCheckRow(id(row, 1), UUID.fromString(check.asString())))).distinct().toList();
        var cases = results.get(4).stream().map(row -> new CaseLinkRow(id(row, 0), id(row, 1), row.get(2).asInt())).toList();
        return new Rows(symptoms, checks, questions, causes, causeChecks, cases);
    }

    public record Linkable(UUID id, String kind, String label) {}

    /** 후보 생성과 결과 적용 시 재조회에 같은 현재 권한·승인 조건을 적용한다. */
    public List<Linkable> linkable(UUID systemId, UUID memberId) {
        var params = parameters(List.of(systemId), new Viewer(memberId, false))
                .addValue("system", systemId.toString());
        var rows = query(params.getValues(), List.of("""
                MATCH (sys:HopsP0:System {id: $system})-[:HAS_FEATURE]->(f:HopsP0:Feature)-[:HAS_SYMPTOM]->(n:HopsP0:Symptom)
                WHERE $system IN $staffSystems AND $features[f.id] = $system AND n.it_system_id = $system
                  AND n.review_status = 'APPROVED' AND $sources[n.source_document_version_id] = $system
                  AND n.access_level IN ['EMPLOYEE', 'STAFF']
                RETURN n.id AS id, 'SYMPTOM' AS kind, n.label AS label, n.catalog_key AS key
                UNION ALL
                MATCH (sys:HopsP0:System {id: $system})-[:HAS_CHECK_ITEM]->(n:HopsP0:CheckItem)
                WHERE $system IN $staffSystems AND n.it_system_id = $system
                  AND n.review_status = 'APPROVED' AND $sources[n.source_document_version_id] = $system
                  AND n.access_level IN ['EMPLOYEE', 'STAFF']
                RETURN n.id AS id, 'CHECK_ITEM' AS kind, n.label AS label, n.catalog_key AS key
                """)).getFirst();
        if (rows.stream().anyMatch(row -> !row.isArray() || row.size() != 4
                || row.valueStream().anyMatch(value -> !value.isString() || value.asString().isBlank()))) {
            throw new Unavailable("Neo4j 사례 후보 응답 형식 오류");
        }
        try {
            return rows.stream().sorted(java.util.Comparator.comparing(row -> value(row, 3)))
                    .map(row -> new Linkable(id(row, 0), value(row, 1), value(row, 2))).toList();
        } catch (IllegalArgumentException exception) {
            throw new Unavailable("Neo4j 사례 후보 ID 형식 오류");
        }
    }

    private MapSqlParameterSource parameters(Collection<UUID> systems, Viewer viewer) {
        List<String> staffSystems = List.of();
        Map<String, String> sources = Map.of();
        if (!systems.isEmpty()) {
            var params = new MapSqlParameterSource("systems", systems).addValue("member", viewer.memberId())
                    .addValue("internalRead", viewer.internalRead());
            staffSystems = jdbc.queryForList("""
                    SELECT CAST(id AS text) FROM it_system WHERE id IN (:systems) AND deleted_at IS NULL
                      AND (:internalRead OR primary_owner_member_id = :member)
                    """, params, String.class);
            // 담당자용 출처도 승인된 직원 질문을 선언할 수 있다. 문서 열람 판단은 응답을 조립할 때 별도로 한다.
            sources = pairs("""
                    SELECT v.id, d.it_system_id FROM knowledge_document_version v
                    JOIN knowledge_document d ON d.id = v.knowledge_document_id
                    JOIN it_system s ON s.id = d.it_system_id
                    WHERE s.id IN (:systems) AND s.deleted_at IS NULL AND d.deleted_at IS NULL
                      AND d.review_status = 'APPROVED' AND d.data_split = 'RAG' AND v.review_status = 'APPROVED'
                      AND v.version_no = (SELECT max(v2.version_no) FROM knowledge_document_version v2
                          WHERE v2.knowledge_document_id = d.id AND v2.review_status = 'APPROVED')
                    """, params);
        }
        return new MapSqlParameterSource("staffSystems", staffSystems).addValue("sources", sources)
                .addValue("features", currentFeatures(systems, null));
    }

    private Map<String, String> currentFeatures(Collection<UUID> systems, Collection<UUID> ids) {
        Collection<UUID> selection = systems == null ? ids : systems;
        if (selection.isEmpty()) {
            return Map.of();
        }
        String filter = systems == null ? "f.id" : "s.id";
        return pairs("""
                SELECT f.id, s.id FROM system_feature f JOIN it_system s ON s.id = f.it_system_id
                WHERE f.deleted_at IS NULL AND s.deleted_at IS NULL AND %s IN (:selection)
                """.formatted(filter), new MapSqlParameterSource("selection", selection));
    }

    private Map<String, String> pairs(String sql, MapSqlParameterSource params) {
        return jdbc.query(sql, params, (rs, row) -> Map.entry(rs.getString(1), rs.getString(2))).stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** PostgreSQL 정본 조회 오류와 Neo4j 연결·응답 오류를 구분한다. */
    public static final class Unavailable extends DataAccessResourceFailureException {
        private Unavailable(String message) {
            super(message);
        }
    }

    private List<List<JsonNode>> query(Map<String, ?> params, List<String> cypher) {
        if (password.isBlank()) {
            throw new Unavailable("Neo4j 자격 증명이 없습니다");
        }
        var statements = cypher.stream().map(statement -> Map.of("statement", statement, "parameters", params)).toList();
        try {
            JsonNode response = client.post().uri("/db/{database}/tx/commit", database).body(Map.of("statements", statements))
                    .retrieve().body(JsonNode.class);
            if (response == null || !response.path("errors").isArray() || !response.path("errors").isEmpty()
                    || !response.path("results").isArray() || response.path("results").size() != cypher.size()) {
                throw new Unavailable("Neo4j 트랜잭션 실패");
            }
            return response.path("results").valueStream()
                    .map(result -> result.required("data").valueStream().map(row -> row.required("row")).toList()).toList();
        } catch (RestClientResponseException exception) {
            throw new Unavailable("Neo4j HTTP " + exception.getStatusCode().value());
        } catch (RestClientException | IllegalArgumentException exception) {
            throw new Unavailable("Neo4j 조회 실패 " + exception.getClass().getSimpleName());
        }
    }

    private static UUID id(JsonNode row, int index) {
        return UUID.fromString(value(row, index));
    }

    private static String value(JsonNode row, int index) {
        return row.get(index).asString();
    }

    private static List<String> strings(Collection<UUID> ids) {
        return ids.stream().map(UUID::toString).toList();
    }
}
