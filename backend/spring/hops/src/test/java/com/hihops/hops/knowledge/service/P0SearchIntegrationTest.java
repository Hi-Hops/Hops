package com.hihops.hops.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.hihops.hops.knowledge.domain.AccessLevel;
import com.hihops.hops.knowledge.domain.DocumentType;
import com.hihops.hops.relation.repository.Neo4jRelationReader;
import com.hihops.hops.relation.service.RelationLinkCandidateReader;
import com.hihops.hops.relation.service.RelationSearchService;

@SpringBootTest(properties = {
        "hops.search.offline-sql=false", "hops.knowledge.case-visibility=INDEXED",
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=none", "hops.agent.dispatch-enabled=false", "hops.idempotency.tombstone.enabled=false"
})
@ActiveProfiles("db")
@Transactional
@EnabledIfEnvironmentVariable(named = "HOPS_P0_SEARCH_COMPARE", matches = ".+")
class P0SearchIntegrationTest {
    private static final UUID EMPLOYEE = UUID.fromString("a0000000-0000-7000-8000-000000000101");
    private static final Set<DocumentType> EMPLOYEE_TYPES = Set.of(DocumentType.EMPLOYEE_GUIDE, DocumentType.POLICY, DocumentType.CASE);

    @Autowired JdbcTemplate jdbc;
    @Autowired NamedParameterJdbcTemplate namedJdbc;
    @Autowired KnowledgeSearchService baseline;
    @Autowired HybridKnowledgeSearch hybrid;
    @Autowired RelationSearchService relations;
    @Autowired RelationLinkCandidateReader curator;

    @Test
    void comparesFixedKoreanQueriesAgainstSqlBaseline() throws Exception {
        var cases = new ArrayList<>(KnowledgeSearchQualityTest.CASES);
        cases.add(new KnowledgeSearchQualityTest.Case("IN_PROGRESS", "ELECTRONIC_APPROVAL", "guide-ea-approval-status"));
        cases.add(new KnowledgeSearchQualityTest.Case("예전에 올려둔 서류를 다시 꺼내 보고 싶어요.", "ELECTRONIC_APPROVAL", "guide-ea-document-search"));
        cases.add(new KnowledgeSearchQualityTest.Case("동료에게 원본을 복사하지 않고 열 수 있게 보내려면요?", "FILE_SHARING", "guide-file-share-link"));
        List<List<Integer>> ranks = new ArrayList<>();
        StringBuilder report = new StringBuilder("|질의|기대 문서|기존 SQL|키워드|벡터|결합|결합 ms|\n|---|---|---|---|---|---|---|\n");
        for (var scenario : cases) {
            var query = documentQuery(scenario.query(), system(scenario.systemCode()));
            long start = System.nanoTime();
            var hybridHits = hybrid.search(scenario.query(), query);
            long hybridMs = (System.nanoTime() - start) / 1_000_000;
            List<Integer> row = List.of(
                    rank(baseline.search(query), scenario.expected()),
                    rank(hybrid.search(scenario.query(), query, HybridKnowledgeSearch.Mode.KEYWORD), scenario.expected()),
                    rank(hybrid.search(scenario.query(), query, HybridKnowledgeSearch.Mode.VECTOR), scenario.expected()),
                    rank(hybridHits, scenario.expected()));
            ranks.add(row);
            report.append("|%s|%s|%d|%d|%d|%d|%d|%n".formatted(scenario.query(), scenario.expected(),
                    row.get(0), row.get(1), row.get(2), row.get(3), hybridMs));
        }
        report.append("\n순위 0은 상위 5건에 없음.\n\n|채널|Top 1|Top 3|Top 5|\n|---|---|---|---|\n");
        List<String> channels = List.of("SQL", "Keyword", "Vector", "Hybrid");
        for (int column = 0; column < channels.size(); column++) {
            int index = column;
            report.append("|%s|%d/%d|%d/%d|%d/%d|%n".formatted(channels.get(index),
                    count(ranks, index, 1), cases.size(), count(ranks, index, 3), cases.size(),
                    count(ranks, index, 5), cases.size()));
        }
        Files.writeString(Path.of(System.getenv("HOPS_P0_SEARCH_COMPARE")), report);
        assertThat(ranks.getFirst().get(3)).isEqualTo(1);
        assertThat(ranks.get(10).get(3)).isEqualTo(1);
        assertThat(ranks.get(11).get(3)).isPositive();
        assertThat(ranks.get(12).get(3)).isPositive();
        String unrelated = "월면기지 암석 채취 로봇의 전력 소모량";
        assertThat(hybrid.search(unrelated, documentQuery(unrelated, system("ELECTRONIC_APPROVAL")))).isEmpty();
    }

    @Test
    void usesActualGraphWithoutDisclosingPrivateRowsAndKeepsEmptyDistinctFromFailure() {
        UUID ea = system("ELECTRONIC_APPROVAL");
        UUID owner = jdbc.queryForObject("SELECT primary_owner_member_id FROM it_system WHERE id = ?", UUID.class, ea);
        var query = new RelationSearchService.Query(List.of("문서함", "문서"), Set.of(), Set.of(AccessLevel.EMPLOYEE),
                false, Set.of(ea), EMPLOYEE, false, 5);
        var symptom = relations.search(query).stream().filter(s -> s.catalogKey().equals("sym-ea-doc-not-listed")).findFirst().orElseThrow();
        assertThat(symptom.causes()).isNull();
        assertThat(symptom.cases()).isEmpty();
        assertThat(symptom.basis().visible()).isFalse();
        assertThat(symptom.checkItems()).allSatisfy(item -> {
            assertThat(item.label()).isNull();
            assertThat(item.basis().visible()).isFalse();
            assertThat(item.questions()).isNotEmpty().allSatisfy(question -> assertThat(question.basis().visible()).isFalse());
        }).extracting(item -> item.catalogKey()).contains("check-ea-filter-condition")
                .doesNotContain("check-ea-view-permission-record");
        assertThat(curator.candidates(ea, EMPLOYEE)).isEmpty();
        var candidates = curator.candidates(ea, owner);
        assertThat(candidates).extracting(c -> c.id()).contains(symptom.id());
        assertThat(curator.currentlyLinkable(ea, owner)).containsAll(candidates);
        var staff = new RelationSearchService.Query(query.tokens(), Set.of(), Set.of(AccessLevel.EMPLOYEE, AccessLevel.STAFF),
                true, Set.of(ea), owner, false, 5);
        assertThat(relations.search(staff).stream().filter(s -> s.id().equals(symptom.id())).findFirst().orElseThrow().cases())
                .isNotEmpty();

        var unknown = new RelationSearchService.Query(List.of("월면기지"), Set.of(), query.levels(), false,
                Set.of(ea), EMPLOYEE, false, 5);
        assertThat(relations.search(unknown)).isEmpty();
        var disconnected = new Neo4jRelationReader(namedJdbc, "http://127.0.0.1:1", "neo4j", "isolated", "neo4j");
        assertThatThrownBy(() -> disconnected.search(query)).isInstanceOf(DataAccessException.class);

        // 같은 실데이터에서 직원용 문서를 제외한 정상 0건. 테스트 트랜잭션은 되돌린다.
        jdbc.update("UPDATE knowledge_document SET data_split = 'EVAL' WHERE access_level = 'EMPLOYEE'");
        String text = "전자결재에서 상신한 문서가 문서함에 안 보여요";
        assertThat(hybrid.search(text, documentQuery(text, ea))).isEmpty();
        assertThat(relations.search(query)).anySatisfy(hit -> assertThat(hit.checkItems()).isNotEmpty());
        jdbc.update("UPDATE knowledge_document SET review_status = 'PENDING' WHERE source_data ->> 'documentKey' = ?",
                "runbook-ea-document-not-listed");
        assertThat(relations.search(query)).noneSatisfy(hit -> assertThat(hit.id()).isEqualTo(symptom.id()));
        assertThat(curator.currentlyLinkable(ea, owner)).noneSatisfy(hit -> assertThat(hit.id()).isEqualTo(symptom.id()));
    }

    private UUID system(String code) {
        return jdbc.queryForObject("SELECT id FROM it_system WHERE code = ?", UUID.class, code);
    }

    private static KnowledgeSearchService.Query documentQuery(String text, UUID system) {
        return new KnowledgeSearchService.Query(KnowledgeQueryTokens.of(text), Set.of(AccessLevel.EMPLOYEE),
                EMPLOYEE_TYPES, Set.of(system), EMPLOYEE, 5);
    }

    private static int rank(List<KnowledgeSearchService.Hit> hits, String key) {
        return hits.stream().map(hit -> hit.assembled().documentKey()).toList().indexOf(key) + 1;
    }

    private static long count(List<List<Integer>> ranks, int index, int max) {
        return ranks.stream().filter(row -> row.get(index) > 0 && row.get(index) <= max).count();
    }
}
