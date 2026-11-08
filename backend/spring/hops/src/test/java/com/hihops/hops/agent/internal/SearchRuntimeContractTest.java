package com.hihops.hops.agent.internal;

import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.hihops.hops.knowledge.service.QueryEmbedding;

/** CI에서 유료 호출 없이 런타임 검색 경로의 장애와 레거시 청크를 확인한다. */
@TestPropertySource(properties = {
        "hops.search.offline-sql=false", "hops.knowledge.case-visibility=INDEXED",
        "hops.search.embedding-api-key=", "hops.neo4j.password="
})
class SearchRuntimeContractTest extends InternalRunApiTestBase {
    private static final String GUIDE_QUERY = "전자결재에서 지난 문서 찾기";

    @MockitoSpyBean
    private QueryEmbedding embedding;

    @Override
    String endpoint() {
        return "knowledge-searches";
    }

    @Test
    void 검색_자격_증명_없음은_문서와_관계_모두_503으로_구분한다() throws Exception {
        search(guideRequest()).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SEARCH_UNAVAILABLE"));
        mvc.perform(post("/internal/v2/runs/" + run + "/relation-searches")
                        .header("Authorization", "Bearer " + TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("inquiryVersion", inquiryVersion,
                                "problemRef", "new:1", "keywords", List.of("문서함"), "systemIds", List.of(eaSystem)))))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SEARCH_UNAVAILABLE"));
    }

    @Test
    void 모델_표시_없는_청크는_키워드로_찾되_오래된_승인_버전은_제외한다() throws Exception {
        // 과거 3차원 벡터는 코사인 연산에서 제외하고 승인된 원문만 키워드 후보가 된다.
        doReturn("[1" + ",0".repeat(QueryEmbedding.DIMENSIONS - 1) + "]").when(embedding).embed(GUIDE_QUERY);
        jdbc.update("""
                INSERT INTO hops_knowledge.knowledge_chunk
                    (knowledge_document_version_id, chunk_no, content, embedding, content_digest, chunk_digest)
                SELECT v.id, 1, v.content, '[1,0,0]'::vector, v.content_digest, v.content_digest
                FROM knowledge_document_version v JOIN knowledge_document d ON d.id = v.knowledge_document_id
                WHERE d.source_data ->> 'documentKey' = 'guide-ea-document-search'
                """);
        search(guideRequest()).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("FOUND"))
                .andExpect(jsonPath("$.results[0].documentKey").value("guide-ea-document-search"))
                .andExpect(jsonPath("$.results[0].evidence.documentVersion").value(1));

        jdbc.update("""
                INSERT INTO knowledge_document_version
                    (knowledge_document_id, version_no, content, content_digest, source_metadata, review_status)
                SELECT v.knowledge_document_id, 2, v.content, v.content_digest, v.source_metadata, 'APPROVED'
                FROM knowledge_document_version v JOIN knowledge_document d ON d.id = v.knowledge_document_id
                WHERE d.source_data ->> 'documentKey' = 'guide-ea-document-search'
                """);
        search(guideRequest()).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("NO_RESULT"))
                .andExpect(jsonPath("$.results").isEmpty());
    }

    private Map<String, Object> guideRequest() {
        return Map.of("inquiryVersion", inquiryVersion, "problemRef", "new:1",
                "query", GUIDE_QUERY, "kinds", List.of("GUIDE"), "systemIds", List.of(eaSystem));
    }
}
