package com.hihops.hops.knowledge.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.hihops.hops.agent.client.AgentRunClient;
import com.hihops.hops.agent.service.AgentDispatchConfig;
import com.hihops.hops.ticket.controller.TicketCommandFixture;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 사례 API 테스트의 공통 준비. 사례 초안은 실제 처리 기록 저장 → Curator 결과 처리(CurationResultHandler) 흐름으로 만든다.
 *
 * <p>사례 조회·수정은 그 저장 형식(confirmed_facts·relation_candidates)을 그대로 읽고 고쳐야 하므로 테스트에서 행을 직접 넣지 않는다.
 * AI 서버만 가짜 응답으로 바꾼다.
 */
@SpringBootTest(properties = "hops.agent.dispatch-enabled=true")
public abstract class CaseDraftFixture extends TicketCommandFixture {
    protected static final List<String> LIMITATIONS = List.of("관리자 권한 필요", "구버전에는 적용 불가");

    @MockitoBean
    protected AgentRunClient client;

    @Autowired
    @Qualifier(AgentDispatchConfig.AGENT_DISPATCH_EXECUTOR)
    private ThreadPoolTaskExecutor dispatchExecutor;

    /** 승인 뒤 색인 전달은 전용 스레드에서 돈다. 다음 테스트의 가짜 AI를 건드리지 않게 끝난 뒤 문서를 지운다. */
    @AfterEach
    void 검색_문서_정리() {
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() ->
                dispatchExecutor.getActiveCount() == 0 && dispatchExecutor.getThreadPoolExecutor().getQueue().isEmpty());
        jdbc.update("""
                DELETE FROM knowledge_document_version WHERE knowledge_document_id IN
                (SELECT id FROM knowledge_document WHERE document_type='CASE' AND it_system_id IN (?,?))""", system, otherSystem);
        jdbc.update("DELETE FROM knowledge_document WHERE document_type='CASE' AND it_system_id IN (?,?)", system, otherSystem);
    }

    @BeforeEach
    void 문의자_원문() {
        // Curator는 해당 문제의 USER 원문만 사례 출처로 인정한다.
        var payload = mapper.createObjectNode();
        payload.putArray("problemLinks").addObject().put("problemId", problem.toString()).put("revision", 1)
                .putArray("spans").addObject().put("start", 0).put("end", 5);
        jdbc.update("UPDATE message SET sender_type='USER',content='결재 오류',payload=?::jsonb WHERE id=?",
                payload.toString(), message);
    }

    /** 담당자 해결 기록을 저장하고 Curator가 재사용 가능한 초안을 저장할 때까지 기다린다. */
    protected UUID draft() throws Exception {
        when(client.snapshot(any())).thenAnswer(invocation -> reusableSnapshot(invocation.getArgument(0, String.class)));
        result(command(staff, "/tickets/" + ticket + "/work-records", work("VERIFIED_RESOLUTION")));
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(detail().path("caseGeneration").path("state").asString()).isEqualTo("SUCCEEDED"));
        return UUID.fromString(detail().path("caseGeneration").path("caseId").asString());
    }

    /** 초안을 만들고 문의자가 해결됐다고 확인한 상태. */
    protected UUID readyDraft() throws Exception {
        UUID caseId = draft();
        String resolutionId = detail().path("activeResolutionRequest").path("id").asString();
        result(command(requester, "/resolution-requests/" + resolutionId + "/responses", mapper.createObjectNode()
                .put("requestId", UUID.randomUUID().toString()).put("expectedRequestRevision", 1)
                .put("decision", "RESOLVED")));
        return caseId;
    }

    protected JsonNode reusableSnapshot(String runId) {
        var result = mapper.createObjectNode().put("contractVersion", "hops-system-1")
                .put("runId", runId).put("kind", "CASE_DRAFT")
                .put("inquiryId", inquiry.toString()).put("inquiryVersion", 1).put("status", "SUCCEEDED");
        result.putNull("answer"); result.putNull("guide"); result.putNull("error");
        for (var field : List.of("problemProposals", "questions", "ticketProposals", "evidence")) result.putArray(field);
        ObjectNode proposal = result.putObject("caseProposal");
        proposal.put("reusable", true).put("title", "가상 사례").put("action", "모델 제안 조치")
                .put("verification", "모델 제안 검증").put("problemStatement", "가상 문제")
                .put("applicability", "가상 환경 전용").put("accessScope", "SYSTEM_STAFF");
        proposal.putNull("appliesToSystemVersion");
        proposal.set("limitations", mapper.valueToTree(LIMITATIONS));
        proposal.putArray("sourceMessageIds").add(message.toString());
        proposal.putArray("entityLinks").addObject().put("relation", "APPLIES_TO").put("targetId", system.toString())
                .putArray("evidenceIds").add(message.toString());
        var snapshot = mapper.createObjectNode().put("status", "SUCCEEDED");
        snapshot.set("result", result);
        return snapshot;
    }
}
