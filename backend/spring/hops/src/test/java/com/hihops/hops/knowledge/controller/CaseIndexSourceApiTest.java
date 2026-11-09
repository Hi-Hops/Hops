package com.hihops.hops.knowledge.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * AI 색인 작업이 읽는 승인 사례 색인 원본(GET /internal/v2/cases/{caseId}/index-source).
 * 승인 거래가 만든 검색 문서 버전의 ID·본문·digest를 서비스 토큰으로만 돌려준다.
 */
@TestPropertySource(properties = "SPRING_SERVICE_TOKEN=" + CaseIndexSourceApiTest.TOKEN)
class CaseIndexSourceApiTest extends CaseDraftFixture {
    static final String TOKEN = "dGVzdC10b2tlbi1mb3ItY2FzZS1pbmRleC1zb3VyY2U";

    @Test
    void 승인된_사례의_색인_원본은_승인_때_저장한_문서_버전과_같다() throws Exception {
        UUID caseId = approved("PENDING");

        JsonNode body = mapper.readTree(mvc.perform(source(caseId, 1)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        var stored = jdbc.queryForMap("""
                SELECT v.id, v.content, v.content_digest FROM knowledge_document_version v
                JOIN knowledge_document d ON d.id = v.knowledge_document_id
                WHERE d.document_type = 'CASE' AND v.source_metadata ->> 'caseId' = ?""", caseId.toString());
        assertThat(body.path("contractVersion").asString()).isEqualTo("hops-system-1");
        assertThat(body.path("caseId").asString()).isEqualTo(caseId.toString());
        assertThat(body.path("caseVersion").asInt()).isEqualTo(1);
        assertThat(body.path("documentVersionId").asString()).isEqualTo(stored.get("id").toString());
        assertThat(body.path("content").asString()).isEqualTo(stored.get("content"));
        assertThat(body.path("contentDigest").asString()).isEqualTo(stored.get("content_digest"));
        assertThat(body.path("title").asString()).isNotBlank();
    }

    @Test
    void 다른_버전과_없는_사례는_구분하지_않고_404다() throws Exception {
        UUID caseId = approved("PENDING");

        mvc.perform(source(caseId, 2)).andExpect(status().isNotFound());
        mvc.perform(source(UUID.randomUUID(), 1)).andExpect(status().isNotFound());
    }

    @Test
    void 승인_전_초안은_404다() throws Exception {
        UUID draft = readyDraft();

        mvc.perform(source(draft, 1)).andExpect(status().isNotFound());
    }

    @Test
    void 두_저장소_반영이_모두_끝난_사례는_다시_읽지_않는다() throws Exception {
        UUID caseId = approved("SUCCEEDED");

        mvc.perform(source(caseId, 1)).andExpect(status().isNotFound());
    }

    @Test
    void 서비스_토큰이_없으면_거부한다() throws Exception {
        UUID caseId = approved("PENDING");

        mvc.perform(get("/internal/v2/cases/" + caseId + "/index-source").param("caseVersion", "1"))
                .andExpect(status().isUnauthorized());
    }

    /** 승인하고 AI 접수 응답을 state로 둔다. SUCCEEDED면 두 저장소 모두 반영이 끝난다. */
    private UUID approved(String state) throws Exception {
        doAnswer(call -> {
            JsonNode request = call.getArgument(0);
            ObjectNode snapshot = mapper.createObjectNode().put("contractVersion", "hops-system-1");
            for (String field : new String[] {"jobId", "caseId", "store", "contentDigest"}) {
                snapshot.put(field, request.path(field).asString());
            }
            snapshot.put("caseVersion", request.path("caseVersion").asInt())
                    .put("attempt", request.path("attempt").asInt()).put("state", state).putNull("error")
                    .put("updatedAt", java.time.Instant.now().toString());
            return snapshot;
        }).when(client).applyIndex(any());
        UUID caseId = readyDraft();
        result(command(staff, "/cases/" + caseId + "/approvals", mapper.createObjectNode()
                .put("requestId", UUID.randomUUID().toString()).put("expectedCaseVersion", 1)));
        awaitDeliveries();
        return caseId;
    }

    private MockHttpServletRequestBuilder source(UUID caseId, int caseVersion) {
        return get("/internal/v2/cases/" + caseId + "/index-source").param("caseVersion", String.valueOf(caseVersion))
                .header("Authorization", "Bearer " + TOKEN);
    }
}
