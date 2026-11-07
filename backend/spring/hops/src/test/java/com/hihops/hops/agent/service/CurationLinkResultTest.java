package com.hihops.hops.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.hihops.hops.agent.client.AgentRunClient;
import com.hihops.hops.ticket.controller.TicketCommandFixture;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Curator 결과의 기존 증상·확인 항목 연결(ADDRESSES·USES_CHECK)을 Spring이 AI 출력을 믿지 않고 다시 확인하는지 실제 DB에서 검사한다(#198 ②-2).
 * AI 서버 응답은 대역이다. 모델 품질이나 실제 AI 연동은 검증하지 않는다.
 */
@org.springframework.boot.test.context.SpringBootTest(properties = "hops.agent.dispatch-enabled=true")
class CurationLinkResultTest extends TicketCommandFixture {
    @MockitoBean
    private AgentRunClient client;
    private CurationRelationRows rows;
    private UUID symptom;
    private UUID check;
    private UUID aiMessage;

    @BeforeEach
    void 준비_연결() {
        var payload = mapper.createObjectNode();
        payload.putArray("problemLinks").addObject().put("problemId", problem.toString()).put("revision", 1)
                .putArray("spans").addObject().put("start", 0).put("end", 5);
        jdbc.update("UPDATE message SET sender_type='USER',content='결재 오류',payload=?::jsonb WHERE id=?", payload.toString(), message);
        aiMessage = UUID.randomUUID();
        jdbc.update("INSERT INTO message(id,inquiry_id,sequence_no,sender_type,content,message_status,payload) VALUES (?,?,2,'AI','어떤 오류인가요?','COMPLETED',?::jsonb)",
                aiMessage, inquiry, "{\"problemLinks\":[{\"problemId\":\"" + problem + "\",\"revision\":1,\"spans\":[{\"start\":0,\"end\":8}]}]}");
        rows = new CurationRelationRows(jdbc, system);
        symptom = rows.symptom(system, "sym", "결재 오류가 남", "EMPLOYEE", "APPROVED");
        check = rows.check(system, "chk", "결재 권한 확인", true, "EMPLOYEE", "APPROVED");
    }

    @AfterEach
    void 정리_연결() {
        rows.clear();
    }

    private UUID recordId() {
        return jdbc.queryForObject("SELECT id FROM ticket_work_log WHERE ticket_id=? ORDER BY created_at DESC LIMIT 1", UUID.class, ticket);
    }

    private ObjectNode snapshot(String runId, List<ObjectNode> links) {
        var result = mapper.createObjectNode().put("contractVersion", "hops-system-1").put("runId", runId)
                .put("kind", "CASE_DRAFT").put("inquiryId", inquiry.toString()).put("inquiryVersion", 1).put("status", "SUCCEEDED");
        result.putNull("answer"); result.putNull("guide"); result.putNull("error");
        for (var field : List.of("problemProposals", "questions", "ticketProposals", "evidence")) result.putArray(field);
        var proposal = result.putObject("caseProposal");
        proposal.put("reusable", true).put("title", "가상 사례").put("problemStatement", "가상 문제")
                .put("action", "모델 제안 조치").put("verification", "모델 제안 검증").put("applicability", "가상 환경 전용")
                .put("accessScope", "SYSTEM_STAFF");
        proposal.putNull("appliesToSystemVersion");
        proposal.putArray("limitations");
        proposal.putArray("sourceMessageIds").add(message.toString());
        var all = proposal.putArray("entityLinks");
        all.add(link("APPLIES_TO", system, message));
        links.forEach(all::add);
        var snapshot = mapper.createObjectNode().put("status", "SUCCEEDED");
        snapshot.set("result", result);
        return snapshot;
    }

    private ObjectNode link(String relation, UUID target, UUID... evidence) {
        var link = mapper.createObjectNode().put("relation", relation).put("targetId", target.toString());
        var ids = link.putArray("evidenceIds");
        for (var id : evidence) ids.add(id.toString());
        return link;
    }

    private void answerWith(java.util.function.Supplier<List<ObjectNode>> links) {
        when(client.snapshot(any())).thenAnswer(invocation -> snapshot(invocation.getArgument(0, String.class), links.get()));
    }

    private String caseState() throws Exception {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(detail().path("caseGeneration").path("state").asString()).isIn("SUCCEEDED", "FAILED"));
        return detail().path("caseGeneration").path("state").asString();
    }

    private JsonNode storedLinks() {
        return json(jdbc.queryForObject("SELECT relation_candidates::text FROM resolved_case WHERE source_work_log_id IN (SELECT id FROM ticket_work_log WHERE ticket_id=?)", String.class, ticket));
    }

    private void expectRejected() throws Exception {
        assertThat(caseState()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT error_code FROM agent_run WHERE ticket_id=?", String.class, ticket)).isEqualTo("INVALID_OUTPUT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resolved_case WHERE source_work_log_id IN (SELECT id FROM ticket_work_log WHERE ticket_id=?)", Integer.class, ticket)).isZero();
        // 사례 초안만 거절하고 처리 기록과 확인 요청은 보존한다.
        assertThat(detail().path("workRecords").size()).isEqualTo(1);
        assertThat(detail().path("activeResolutionRequest").path("state").asString()).isEqualTo("PENDING");
    }

    @Test
    void 증상_연결은_AI_메시지도_근거로_쓰고_확인_연결은_처리_기록과_사용자_메시지를_근거로_보존한다() throws Exception {
        answerWith(() -> List.of(
                link("ADDRESSES", symptom, message, aiMessage),
                link("USES_CHECK", check, recordId(), message)));

        result(command(staff, "/tickets/" + ticket + "/work-records", work("VERIFIED_RESOLUTION")));

        assertThat(caseState()).isEqualTo("SUCCEEDED");
        var links = storedLinks();
        assertThat(links.size()).isEqualTo(3);
        assertThat(links.get(1).path("relation").asString()).isEqualTo("ADDRESSES");
        assertThat(links.get(1).path("targetId").asString()).isEqualTo(symptom.toString());
        assertThat(links.get(2).path("relation").asString()).isEqualTo("USES_CHECK");
        assertThat(links.get(2).path("evidenceIds").size()).isEqualTo(2);
        // 사례 출처는 계속 사용자 메시지뿐이다.
        var metadata = json(jdbc.queryForObject("SELECT confirmed_facts::text FROM resolved_case WHERE source_work_log_id IN (SELECT id FROM ticket_work_log WHERE ticket_id=?)", String.class, ticket));
        assertThat(metadata.path("sourceMessageIds")).isEqualTo(mapper.valueToTree(List.of(message)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "unknown-target", "not-offered", "withdrawn", "other-system", "kind-mismatch-address", "kind-mismatch-check",
            "unknown-relation", "cause-relation", "evidence-unknown", "evidence-empty", "evidence-other-problem",
            "duplicate", "too-many-addresses", "check-ai-evidence", "check-mixed-ai-evidence"})
    void 후보가_아니거나_근거가_없거나_관계_유형이_다른_연결은_초안만_거절한다(String invalid) throws Exception {
        var otherSystemNode = new ArrayList<UUID>();
        var moreSymptoms = new ArrayList<UUID>();
        var otherProblemMessage = UUID.randomUUID();
        if ("other-system".equals(invalid)) {
            jdbc.update("UPDATE it_system SET primary_owner_member_id=? WHERE id=?", staff, otherSystem);
            otherSystemNode.add(rows.symptom(otherSystem, "other", "다른 시스템 증상", "EMPLOYEE", "APPROVED"));
        }
        if ("too-many-addresses".equals(invalid)) {
            for (int index = 0; index < 3; index++) moreSymptoms.add(rows.symptom(system, "more" + index, "증상 " + index, "EMPLOYEE", "APPROVED"));
        }
        answerWith(() -> {
            switch (invalid) {
                case "not-offered" -> {
                    // 입력(후보)을 고정한 뒤에 만들어진 승인 노드는 AI가 본 적이 없으므로 연결할 수 없다.
                    var late = rows.symptom(system, "late", "나중에 생긴 증상", "EMPLOYEE", "APPROVED");
                    return List.of(link("ADDRESSES", late, message));
                }
                case "withdrawn" -> {
                    jdbc.update("UPDATE relation_symptom SET review_status='PENDING' WHERE id=?", symptom);
                    return List.of(link("ADDRESSES", symptom, message));
                }
                case "unknown-target" -> { return List.of(link("ADDRESSES", UUID.randomUUID(), message)); }
                case "other-system" -> { return List.of(link("ADDRESSES", otherSystemNode.getFirst(), message)); }
                case "kind-mismatch-address" -> { return List.of(link("ADDRESSES", check, message)); }
                case "kind-mismatch-check" -> { return List.of(link("USES_CHECK", symptom, message)); }
                case "unknown-relation" -> { return List.of(link("DEPENDS_ON", symptom, message)); }
                case "cause-relation" -> { return List.of(link("MAY_HAVE_CAUSE", check, message)); }
                case "evidence-unknown" -> { return List.of(link("ADDRESSES", symptom, UUID.randomUUID())); }
                case "evidence-empty" -> { return List.of(link("ADDRESSES", symptom)); }
                case "evidence-other-problem" -> { return List.of(link("ADDRESSES", symptom, otherProblemMessage)); }
                // 확인했다는 연결(USES_CHECK)은 AI가 물은 메시지를 근거로 인정하지 않는다
                case "check-ai-evidence" -> { return List.of(link("USES_CHECK", check, aiMessage)); }
                case "check-mixed-ai-evidence" -> { return List.of(link("USES_CHECK", check, recordId(), aiMessage)); }
                case "duplicate" -> { return List.of(link("ADDRESSES", symptom, message), link("ADDRESSES", symptom, message)); }
                case "too-many-addresses" -> {
                    var links = new ArrayList<ObjectNode>();
                    links.add(link("ADDRESSES", symptom, message));
                    moreSymptoms.forEach(id -> links.add(link("ADDRESSES", id, message)));
                    return links;
                }
                default -> throw new IllegalArgumentException(invalid);
            }
        });

        result(command(staff, "/tickets/" + ticket + "/work-records", work("VERIFIED_RESOLUTION")));

        expectRejected();
    }

    @Test
    void 다른_문제의_메시지는_관계_근거로_쓸_수_없다() throws Exception {
        var otherProblem = UUID.randomUUID();
        jdbc.update("INSERT INTO inquiry_problem(id,inquiry_id,problem_no,title,problem_status) VALUES (?,?,2,'다른 문제','CLARIFYING')", otherProblem, inquiry);
        var foreign = UUID.randomUUID();
        jdbc.update("INSERT INTO message(id,inquiry_id,sequence_no,sender_type,content,message_status,payload) VALUES (?,?,3,'USER','다른 문제의 말','COMPLETED',?::jsonb)",
                foreign, inquiry, "{\"problemLinks\":[{\"problemId\":\"" + otherProblem + "\",\"revision\":1,\"spans\":[{\"start\":0,\"end\":6}]}]}");
        answerWith(() -> List.of(link("ADDRESSES", symptom, foreign)));

        result(command(staff, "/tickets/" + ticket + "/work-records", work("VERIFIED_RESOLUTION")));

        expectRejected();
    }
}
