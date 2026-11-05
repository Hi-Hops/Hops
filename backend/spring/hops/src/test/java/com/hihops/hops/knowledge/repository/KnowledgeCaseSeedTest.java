package com.hihops.hops.knowledge.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시드 해결 사례(R__seed_dev_knowledge_cases.sql)를 실제로 적재해 정본 7절과 같은지, 이미 병합된 조회 API가 읽을 수 있는지 확인한다.
 * 이 파일은 docs/knowledge/cases의 사례 카드에서 자동 생성된다. 카드와 SQL이 어긋났는지는 scripts/knowledge의 검사가 맡는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"db", "test"})
@Transactional
class KnowledgeCaseSeedTest {

    private static final String EA_OWNER = "a0000000-0000-7000-8000-000000000011";
    private static final String LOGIN_OWNER = "a0000000-0000-7000-8000-000000000012";
    private static final String FILE_OWNER = "a0000000-0000-7000-8000-000000000013";
    private static final String REQUESTER1 = "a0000000-0000-7000-8000-000000000101";
    private static final String REQUESTER3 = "a0000000-0000-7000-8000-000000000103";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mockMvc;
    @Autowired private KnowledgeDocumentVersionRepository versions;

    @Test
    void 사례_6건과_이력_체인이_정본의_행_수대로_들어간다() throws Exception {
        loadSeeds();

        assertThat(count("SELECT count(*) FROM inquiry WHERE inquiry_number LIKE 'SEED-Q-%'")).isEqualTo(6);
        assertThat(count("SELECT count(*) FROM message")).isEqualTo(24);
        assertThat(count("SELECT count(*) FROM agent_run WHERE task = 'INQUIRY_ANALYSIS'")).isEqualTo(12);
        assertThat(count("SELECT count(*) FROM agent_run WHERE task = 'CURATION'")).isEqualTo(6);
        assertThat(count("SELECT count(*) FROM inquiry_problem")).isEqualTo(6);
        assertThat(count("SELECT count(*) FROM standard_request")).isEqualTo(6);
        assertThat(count("SELECT count(*) FROM ticket WHERE ticket_number LIKE 'SEED-T-%' AND ticket_status = 'RESOLVED'")).isEqualTo(6);
        assertThat(count("SELECT count(*) FROM ticket_assignment WHERE ended_at IS NULL")).isEqualTo(6);
        assertThat(count("SELECT count(*) FROM ticket_work_log")).isEqualTo(12);
        assertThat(count("SELECT count(*) FROM ticket_event")).isEqualTo(12);
        assertThat(count("SELECT count(*) FROM resolved_case WHERE case_number LIKE 'SEED-C-%'")).isEqualTo(6);
        assertThat(count("SELECT count(*) FROM knowledge_document WHERE document_type = 'CASE'")).isEqualTo(6);
        assertThat(count("SELECT count(*) FROM knowledge_document")).isEqualTo(22);
    }

    @Test
    void 사례는_담당자_전용_합성_승인이고_색인은_PENDING이다() throws Exception {
        loadSeeds();

        assertThat(count("SELECT count(*) FROM resolved_case WHERE case_status = 'APPROVED' AND access_level = 'STAFF' "
                + "AND origin_type = 'SYNTHETIC' AND case_type = 'RESOLUTION' AND approved_by_member_id IS NOT NULL "
                + "AND vector_status = 'PENDING' AND graph_status = 'PENDING' AND curator_run_id IS NOT NULL")).isEqualTo(6);
        // 승인자는 해당 시스템의 주담당자이고 처리한 담당자와 같다.
        assertThat(count("SELECT count(*) FROM resolved_case c JOIN it_system s ON s.id = c.it_system_id "
                + "JOIN ticket_work_log w ON w.id = c.source_work_log_id "
                + "WHERE c.approved_by_member_id = s.primary_owner_member_id AND w.author_member_id = s.primary_owner_member_id"))
                .isEqualTo(6);
        // 사례의 지식 문서 ID는 resolved_case.id와 같다(DATA.md 11.3).
        assertThat(count("SELECT count(*) FROM resolved_case c JOIN knowledge_document d ON d.id = c.id "
                + "WHERE d.document_type = 'CASE' AND d.access_level = 'STAFF' AND d.it_system_id = c.it_system_id")).isEqualTo(6);
    }

    @Test
    void 문서_스냅샷이_승인_거래와_같은_형식으로_반영_요청을_보존한다() throws Exception {
        loadSeeds();

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT v.content, v.content_digest, v.source_metadata ->> 'caseId' AS case_id, v.source_metadata ->> 'caseVersion' AS case_version, "
                        + "d.title AS doc_title, c.title AS case_title, c.problem_statement, c.approved_by_member_id::text AS approver, "
                        + "v.source_metadata -> 'indexRequests' -> 'VECTOR' ->> 'attempt' AS vector_attempt, "
                        + "v.source_metadata -> 'indexRequests' -> 'NEO4J' ->> 'store' AS graph_store, "
                        + "v.source_metadata -> 'indexRequests' -> 'VECTOR' -> 'approvedCase' ->> 'approvedBy' AS snapshot_approver, "
                        + "v.source_metadata -> 'indexRequests' -> 'VECTOR' -> 'approvedCase' ->> 'confirmationOrigin' AS confirmation_origin, "
                        + "v.source_metadata -> 'indexRequests' -> 'VECTOR' ->> 'jobId' AS vector_job, "
                        + "v.source_metadata -> 'indexRequests' -> 'NEO4J' ->> 'jobId' AS graph_job "
                        + "FROM knowledge_document_version v JOIN knowledge_document d ON d.id = v.knowledge_document_id "
                        + "JOIN resolved_case c ON c.id = d.id WHERE d.document_type = 'CASE'");
        assertThat(rows).hasSize(6);
        for (Map<String, Object> row : rows) {
            // 본문은 승인 거래(CaseApprovalWriter)와 같은 순서: 제목, 증상, 조치, 검증, 적용 조건, 한계
            String content = (String) row.get("content");
            assertThat(content).startsWith((String) row.get("case_title")).contains((String) row.get("problem_statement"));
            assertThat(content).isEqualTo(content.strip());
            assertThat(((String) row.get("content_digest")).trim()).matches("[0-9a-f]{64}");
            assertThat(row.get("doc_title")).isEqualTo(row.get("case_title"));
            assertThat(row.get("case_version")).isEqualTo("1");
            // 저장소별 반영 요청을 보존한다(전달 전). 승인 스냅샷의 승인자와 확인 출처가 사례 행과 일치한다.
            assertThat(row.get("vector_attempt")).isEqualTo("1");
            assertThat(row.get("graph_store")).isEqualTo("NEO4J");
            assertThat(row.get("vector_job")).isNotEqualTo(row.get("graph_job"));
            assertThat(row.get("snapshot_approver")).isEqualTo(row.get("approver"));
            assertThat(row.get("confirmation_origin")).isEqualTo("SYNTHETIC_PRESET");
            assertThat(count("SELECT count(*) FROM resolved_case WHERE id = '" + row.get("case_id") + "'")).isEqualTo(1);
        }
        // 관계 후보의 APPLIES_TO 대상은 사례의 시스템이다.
        assertThat(count("SELECT count(*) FROM resolved_case c WHERE c.relation_candidates -> 0 ->> 'targetId' = CAST(c.it_system_id AS text) "
                + "AND c.relation_candidates -> 0 ->> 'relation' = 'APPLIES_TO'")).isEqualTo(6);
    }

    @Test
    void 사례_검토_API는_시스템_주담당자에게만_자기_시스템_사례_2건씩을_보인다() throws Exception {
        loadSeeds();

        for (String owner : List.of(EA_OWNER, LOGIN_OWNER, FILE_OWNER)) {
            mockMvc.perform(get("/api/v2/cases").with(auth(owner)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(2))
                    .andExpect(jsonPath("$.items[0].latestState").value("APPROVED"))
                    .andExpect(jsonPath("$.items[0].generationState").value("SUCCEEDED"))
                    // 색인이 PENDING이라 아직 검색 공개 버전이 없다.
                    .andExpect(jsonPath("$.items[0].publishedVersions").isEmpty());
        }
        mockMvc.perform(get("/api/v2/cases").with(auth(REQUESTER1)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 사례_상세는_해결_확인과_원인_조치_검증을_읽는다() throws Exception {
        loadSeeds();
        UUID caseId = jdbc.queryForObject("SELECT id FROM resolved_case WHERE case_number = 'SEED-C-003'", UUID.class);

        mockMvc.perform(get("/api/v2/cases/" + caseId).with(auth(LOGIN_OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version.caseType").value("RESOLUTION"))
                .andExpect(jsonPath("$.version.accessScope").value("SYSTEM_STAFF"))
                .andExpect(jsonPath("$.version.origin").value("SYNTHETIC"))
                .andExpect(jsonPath("$.version.confirmationOrigin").value("SYNTHETIC_PRESET"))
                .andExpect(jsonPath("$.version.sourceMessageIds.length()").value(2))
                .andExpect(jsonPath("$.approval.approvedBy").exists())
                // 승인 때 저장한 저장소별 반영 요청이 PENDING(전달 전)으로 보인다.
                .andExpect(jsonPath("$.indexes.length()").value(2))
                .andExpect(jsonPath("$.indexes[0].state").value("PENDING"))
                .andExpect(jsonPath("$.indexes[0].attempt").value(1))
                .andExpect(jsonPath("$.publication.state").value("PRIVATE"))
                .andExpect(jsonPath("$.publication.canSearch").value(false));
        // 다른 시스템의 주담당자에게는 존재를 드러내지 않는다.
        mockMvc.perform(get("/api/v2/cases/" + caseId).with(auth(EA_OWNER)))
                .andExpect(status().isNotFound());
    }

    @Test
    void 문의자는_과거에_해결된_자기_문의를_본다() throws Exception {
        loadSeeds();

        // requester1은 사례 2건, requester3은 사례 2건의 문의자다. 다른 문의자의 문의는 보이지 않는다.
        mockMvc.perform(get("/api/v2/inquiries").with(auth(REQUESTER1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/api/v2/inquiries").with(auth(REQUESTER3)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
        UUID inquiryId = jdbc.queryForObject("SELECT id FROM inquiry WHERE inquiry_number = 'SEED-Q-001'", UUID.class);
        mockMvc.perform(get("/api/v2/inquiries/" + inquiryId).with(auth(REQUESTER1)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v2/inquiries/" + inquiryId).with(auth(REQUESTER3)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v2/inquiries/" + inquiryId + "/messages").with(auth(REQUESTER1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(4))
                .andExpect(jsonPath("$.items[0].sender").value("USER"));
    }

    @Test
    void 사례_문서는_계약_규칙에서는_색인_전이라_제외되고_1차_SQL_규칙에서는_공개된다() throws Exception {
        loadSeeds();

        UUID versionId = jdbc.queryForObject("SELECT v.id FROM knowledge_document_version v JOIN knowledge_document d "
                + "ON d.id = v.knowledge_document_id WHERE d.document_type = 'CASE' AND d.it_system_id = "
                + "(SELECT id FROM it_system WHERE code = 'ELECTRONIC_APPROVAL') LIMIT 1", UUID.class);
        // 계약 규칙(INDEXED)은 Vector·Neo4j 반영이 모두 SUCCEEDED여야 한다. 시드는 색인하지 않았으므로 PENDING이고 공개되지 않는다.
        assertThat(versions.isCurrentlyVisible(versionId, UUID.fromString(EA_OWNER), true)).isFalse();
        // 1차 SQL 검색 규칙(APPROVED_SNAPSHOT)은 승인된 현재 스냅샷이면 공개한다. 색인 상태는 PENDING 그대로다.
        assertThat(versions.isCurrentlyVisible(versionId, UUID.fromString(EA_OWNER), false)).isTrue();
        // 담당자 전용 사례는 다른 시스템 담당자와 문의자에게 어느 규칙에서도 공개되지 않는다.
        assertThat(versions.isCurrentlyVisible(versionId, UUID.fromString(LOGIN_OWNER), false)).isFalse();
        assertThat(versions.isCurrentlyVisible(versionId, UUID.fromString(REQUESTER1), false)).isFalse();
        assertThat(count("SELECT count(*) FROM resolved_case WHERE vector_status = 'PENDING' AND graph_status = 'PENDING'")).isEqualTo(6);
    }

    @Test
    void 두_번_적재해도_행이_늘지_않는다() throws Exception {
        loadSeeds();
        loadSeeds();

        assertThat(count("SELECT count(*) FROM inquiry")).isEqualTo(6);
        assertThat(count("SELECT count(*) FROM message")).isEqualTo(24);
        assertThat(count("SELECT count(*) FROM resolved_case")).isEqualTo(6);
        assertThat(count("SELECT count(*) FROM knowledge_document")).isEqualTo(22);
        assertThat(count("SELECT count(*) FROM inquiry WHERE current_run_id IS NOT NULL")).isEqualTo(6);
    }

    /** 로그인 토큰의 클레임을 DB의 소속·담당 시스템에서 만든다(TicketCommandFixture와 같은 구성). */
    private RequestPostProcessor auth(String memberId) {
        String departmentId = jdbc.queryForObject("SELECT department_id::text FROM member WHERE id = CAST(? AS uuid)", String.class, memberId);
        List<String> primarySystemIds = jdbc.queryForList(
                "SELECT id::text FROM it_system WHERE primary_owner_member_id = CAST(? AS uuid) AND deleted_at IS NULL", String.class, memberId);
        return jwt().jwt(token -> token.subject(memberId).claim("departmentId", departmentId).claim("roles", List.of())
                .claim("policyRevision", 1).claim("primarySystemIds", primarySystemIds).expiresAt(Instant.now().plusSeconds(600)));
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private void loadSeeds() throws Exception {
        // R__ 파일은 이름 순으로 실행된다. 본문이 $kb$ 인용 안에 세미콜론을 포함하므로 문장을 나누지 않고 서버에 통째로 보낸다.
        for (String file : List.of("R__seed_dev_department_member.sql", "R__seed_dev_it_system.sql",
                "R__seed_dev_knowledge.sql", "R__seed_dev_knowledge_cases.sql")) {
            jdbc.execute(new ClassPathResource("db/seed/" + file).getContentAsString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
        }
    }
}
