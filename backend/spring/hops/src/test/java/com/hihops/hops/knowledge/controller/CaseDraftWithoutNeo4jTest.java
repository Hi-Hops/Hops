package com.hihops.hops.knowledge.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {"hops.search.offline-sql=false", "hops.neo4j.password="})
class CaseDraftWithoutNeo4jTest extends CaseDraftFixture {
    @Test
    void 그래프를_쓸_수_없어도_처리기록과_확인요청과_시스템에_연결된_초안을_저장한다() throws Exception {
        var caseId = draft();
        var detail = detail();
        assertThat(detail.path("workRecords").size()).isEqualTo(1);
        assertThat(detail.path("state").asString()).isEqualTo("WAITING_CONFIRMATION");
        assertThat(detail.path("activeResolutionRequest").path("state").asString()).isEqualTo("PENDING");
        var input = json(jdbc.queryForObject("SELECT input_snapshot::text FROM agent_run WHERE ticket_id=?",
                String.class, ticket));
        assertThat(input.path("workRecord").path("linkCandidates").isArray()).isTrue();
        assertThat(input.path("workRecord").path("linkCandidates").size()).isZero();
        var links = json(jdbc.queryForObject("SELECT relation_candidates::text FROM resolved_case WHERE id=?",
                String.class, caseId));
        assertThat(links.size()).isEqualTo(1);
        assertThat(links.get(0).path("relation").asString()).isEqualTo("APPLIES_TO");
    }
}
