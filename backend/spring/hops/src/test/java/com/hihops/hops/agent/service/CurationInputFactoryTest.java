package com.hihops.hops.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hihops.hops.ticket.controller.TicketCommandFixture;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Curator(CASE_DRAFT) 입력이 해당 문제의 문의 전체 과정을 담는지, 그리고 권한·범위·상한을 지키는지 실제 DB에서 확인한다(#198 ①).
 *
 * <p>처리 기록 저장 API가 만든 실행의 입력 스냅샷(agent_run.input_snapshot)을 읽는다. AI 서버 호출은 꺼 둔다(dispatch-enabled=false).
 * 모델 호출이나 AI wire 검증은 포함하지 않으며 그 호환은 AI 쪽 wire 테스트가 따로 확인한다.
 */
class CurationInputFactoryTest extends TicketCommandFixture {
    private final UUID otherProblem = UUID.randomUUID();
    private final UUID document = UUID.randomUUID();
    private int sequence = 1;

    private CurationRelationRows rows;
    private final List<UUID> extraDocuments = new ArrayList<>();

    @AfterEach
    void 지식_정리() {
        if (rows != null) rows.clear();
        for (var extra : extraDocuments) {
            jdbc.update("DELETE FROM knowledge_document_version WHERE knowledge_document_id=?", extra);
            jdbc.update("DELETE FROM knowledge_document WHERE id=?", extra);
        }
        jdbc.update("DELETE FROM knowledge_document_version WHERE knowledge_document_id=?", document);
        jdbc.update("DELETE FROM knowledge_document WHERE id=?", document);
    }

    /** 원문 전체를 이 문제에 잇는 payload. pending이면 보완 대상을 아직 정하지 못한 메시지다. */
    private String links(UUID target, String content, boolean pending) {
        return "{" + (pending ? "\"pendingTarget\":true," : "") + "\"problemLinks\":[{\"problemId\":\"" + target
                + "\",\"revision\":1,\"spans\":[{\"start\":0,\"end\":" + content.codePointCount(0, content.length()) + "}]}]}";
    }

    private UUID addLinked(String sender, String content, UUID target) {
        return addMessage(sender, content, links(target, content, false));
    }

    private UUID addMessage(String sender, String content, String payload) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO message(id,inquiry_id,sequence_no,sender_type,content,message_status,payload) VALUES (?,?,?,?,?,'COMPLETED',?::jsonb)",
                id, inquiry, ++sequence, sender, content, payload);
        return id;
    }

    private UUID analysisFor(UUID answerId, Object messageInput) {
        UUID run = UUID.randomUUID();
        String snapshot = mapper.writeValueAsString(Map.of("answerMessageId", answerId.toString(), "messages", messageInput));
        jdbc.update("INSERT INTO agent_run (id,inquiry_id,task,run_status,input_version,input_snapshot,model_version,prompt_version,schema_version) "
                + "VALUES (?,?,'INQUIRY_ANALYSIS','QUEUED',1,?::jsonb,'test','test','test')", run, inquiry, snapshot);
        return run;
    }

    private JsonNode record(String kind) throws Exception {
        int revision = detail().path("ticketRevision").asInt();
        return result(command(staff, "/tickets/" + ticket + "/work-records",
                ((ObjectNode) work(kind)).put("expectedTicketRevision", revision)));
    }

    /** 가장 최근에 접수된 CURATION 실행의 입력. */
    private JsonNode curationInput() {
        return json(jdbc.queryForObject("SELECT input_snapshot::text FROM agent_run WHERE ticket_id=? AND task='CURATION' ORDER BY created_at DESC, id DESC LIMIT 1",
                String.class, ticket));
    }

    private static List<String> ids(JsonNode values) {
        var result = new ArrayList<String>();
        values.forEach(value -> result.add(value.asString()));
        return result;
    }

    @Test
    void 이_문제의_사용자_메시지와_AI_질문_답변을_순번대로_담고_다른_문제와_연결_없는_메시지는_뺀다() throws Exception {
        jdbc.update("INSERT INTO inquiry_problem(id,inquiry_id,problem_no,title,problem_status) VALUES (?,?,2,'다른 문제','CLARIFYING')", otherProblem, inquiry);
        var userMessage = addLinked("USER", "결재 첨부파일이 안 열려요", problem);
        var question = addLinked("AI", "어떤 파일 형식인가요?", problem);
        var otherProblemAi = addLinked("AI", "다른 문제에 대한 질문입니다", otherProblem);
        var unlinkedAi = addMessage("AI", "안녕하세요 연결되지 않은 인사입니다", "{}");
        var pendingContent = "보완 대상이 정해지지 않은 말";
        var pending = addMessage("USER", pendingContent, links(problem, pendingContent, true));

        record("VERIFIED_RESOLUTION");

        var byId = new java.util.LinkedHashMap<String, JsonNode>();
        curationInput().path("messages").forEach(item -> byId.put(item.path("id").asString(), item));
        // 순번 순서: 기본 AI 안내(1), 사용자(2), 질문(3)
        assertThat(byId.keySet()).containsExactly(message.toString(), userMessage.toString(), question.toString());
        assertThat(byId.get(userMessage.toString()).path("sender").asString()).isEqualTo("USER");
        assertThat(byId.get(question.toString()).path("sender").asString()).isEqualTo("AI");
        assertThat(byId.get(question.toString()).path("content").asString()).isEqualTo("어떤 파일 형식인가요?");
        byId.values().forEach(item -> assertThat(ids(item.path("problemIds"))).containsExactly(problem.toString()));
        assertThat(byId).doesNotContainKeys(otherProblemAi.toString(), unlinkedAi.toString(), pending.toString());
    }

    @Test
    void 이미지_실행의_AI_답변은_담당자_대화와_사례_초안에서_제외한다() throws Exception {
        UUID user = addLinked("USER", "사용자가 입력한 오류 설명", problem);
        UUID imageAnswer = addLinked("AI", "이미지에서 본 미확인 관찰", problem);
        UUID textAnswer = addLinked("AI", "텍스트 전용 질문", problem);
        UUID invalidAnswer = addLinked("AI", "잘못된 이미지 입력의 답변", problem);
        UUID payloadAnswer = addLinked("AI", "실행 식별자로 연결한 이미지 답변", problem);
        analysisFor(imageAnswer, List.of(Map.of("imageAttachments", List.of(Map.of("attachmentId", UUID.randomUUID())))));
        analysisFor(textAnswer, List.of(Map.of("imageAttachments", List.of())));
        analysisFor(invalidAnswer, "invalid-input");
        UUID payloadRun = analysisFor(UUID.randomUUID(), List.of(Map.of("imageAttachments", List.of(Map.of("attachmentId", UUID.randomUUID())))));
        var payload = (ObjectNode) mapper.readTree(links(problem, "실행 식별자로 연결한 이미지 답변", false));
        payload.put("answerRunId", payloadRun.toString());
        jdbc.update("UPDATE message SET payload=?::jsonb WHERE id=?", mapper.writeValueAsString(payload), payloadAnswer);
        jdbc.update("UPDATE standard_request SET confirmed_facts=?::jsonb WHERE id=?", mapper.writeValueAsString(List.of(
                Map.of("text", "이미지 유래 사실", "sourceMessageIds", List.of(imageAnswer.toString()), "knowledgeEvidenceIds", List.of()))), standard);

        var response = mvc.perform(as(staff, get("/api/v2/tickets/{id}/related-messages", ticket)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var visible = json(response).path("items");
        assertThat(visible.toString()).contains(user.toString(), textAnswer.toString())
                .doesNotContain(imageAnswer.toString(), invalidAnswer.toString(), payloadAnswer.toString(), "미확인 관찰");
        String cursor = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("1:" + ticket + ":" + imageAnswer).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        mvc.perform(as(staff, get("/api/v2/tickets/{id}/related-messages", ticket).param("cursor", cursor)))
                .andExpect(status().isBadRequest());
        assertThat(detail().toString()).doesNotContain("이미지 유래 사실");

        record("VERIFIED_RESOLUTION");
        var context = curationInput().path("messages").toString();
        assertThat(context).contains(user.toString(), textAnswer.toString())
                .doesNotContain(imageAnswer.toString(), invalidAnswer.toString(), payloadAnswer.toString(), "미확인 관찰");
        assertThat(curationInput().path("problems").toString()).doesNotContain("이미지 유래 사실");
    }

    @Test
    void AI_메시지는_가장_최근_30개만_담고_사용자_메시지는_모두_담는다() throws Exception {
        var first = addLinked("AI", "오래된 질문 0", problem);
        var latest = new ArrayList<String>();
        for (int index = 1; index < 35; index++) {
            latest.add(addLinked("AI", "질문 " + index, problem).toString());
        }
        var user = addLinked("USER", "마지막 사용자 메시지", problem);

        record("VERIFIED_RESOLUTION");

        var messages = curationInput().path("messages");
        var all = new ArrayList<String>();
        messages.forEach(item -> all.add(item.path("id").asString()));
        long ai = messages.valueStream().filter(item -> "AI".equals(item.path("sender").asString())).count();
        assertThat(ai).isEqualTo(CurationInputFactory.MAX_AI_MESSAGES);
        // 기본 안내(1)와 오래된 질문 0을 포함해 앞쪽 AI 메시지 6개가 빠지고 사용자 메시지는 남는다.
        assertThat(all).doesNotContain(message.toString(), first.toString()).contains(user.toString());
        assertThat(all).contains(latest.getLast());
        assertThat(all).doesNotContain(latest.getFirst());
    }

    @Test
    void 같은_티켓의_이전_처리_기록을_오래된_순으로_담되_원_기록은_제외한다() throws Exception {
        record("PROGRESS");
        record("FAILED_ATTEMPT");
        record("VERIFIED_RESOLUTION");

        var work = curationInput().path("workRecord");
        var related = work.path("relatedRecords");
        assertThat(related.size()).isEqualTo(2);
        assertThat(related.get(0).path("kind").asString()).isEqualTo("PROGRESS");
        assertThat(related.get(1).path("kind").asString()).isEqualTo("FAILED_ATTEMPT");
        assertThat(related.valueStream().map(item -> item.path("id").asString())).doesNotContain(work.path("id").asString());
        assertThat(work.path("kind").asString()).isEqualTo("VERIFIED_RESOLUTION");
        assertThat(related.get(0).path("action").asString()).isEqualTo("실제 조치");
    }

    @Test
    void 처리_기록의_맥락은_가장_최근_20개로_제한하고_빈_문장은_null로_보낸다() throws Exception {
        for (int index = 0; index < 25; index++) {
            jdbc.update("INSERT INTO ticket_work_log(ticket_id,author_member_id,log_type,action_detail,verification_result,confirmed_cause,created_at) VALUES (?,?,'PROGRESS',?,?,?,now() - make_interval(mins => ?))",
                    ticket, staff, "조치 " + index, index == 0 ? "   " : null, index == 0 ? "" : null, 60 - index);
        }

        record("VERIFIED_RESOLUTION");

        var related = curationInput().path("workRecord").path("relatedRecords");
        assertThat(related.size()).isEqualTo(CurationInputFactory.MAX_RELATED_RECORDS);
        // index 0이 가장 오래된 기록이라 상한을 넘는 앞쪽 5개(0~4)가 빠진다. 빈 문장은 가장 오래된 쪽에 있어 남지 않는다.
        assertThat(related.get(0).path("action").asString()).isEqualTo("조치 5");
        assertThat(related.get(19).path("action").asString()).isEqualTo("조치 24");
        assertThat(related.get(0).path("verification").isNull()).isTrue();
    }

    @Test
    void 처리_기록_맥락의_긴_문장은_4000자로_자르고_빈_문장은_null이다() throws Exception {
        jdbc.update("INSERT INTO ticket_work_log(ticket_id,author_member_id,log_type,action_detail,verification_result,confirmed_cause,created_at) VALUES (?,?,'FAILED_ATTEMPT',?,'   ','',now() - interval '1 hour')",
                ticket, staff, "가".repeat(4500));

        record("VERIFIED_RESOLUTION");

        var related = curationInput().path("workRecord").path("relatedRecords");
        assertThat(related.size()).isEqualTo(1);
        assertThat(related.get(0).path("action").asString().codePointCount(0, 4000)).isEqualTo(4000);
        assertThat(related.get(0).path("action").asString()).hasSize(4000);
        assertThat(related.get(0).path("verification").isNull()).isTrue();
        assertThat(related.get(0).path("confirmedCause").isNull()).isTrue();
    }

    @Test
    void 해결_확인_이력은_안내와_처리_기록의_이전_요청을_담고_현재_요청은_제외한다() throws Exception {
        // 안내에 대한 확인: 문의자가 미해결로 응답했다.
        var guideRequest = UUID.randomUUID();
        var guidePayload = mapper.createObjectNode();
        guidePayload.putArray("problemLinks").addObject().put("problemId", problem.toString()).put("revision", 1)
                .putArray("spans").addObject().put("start", 0).put("end", 2);
        guidePayload.putObject("resolutionRequest").put("id", guideRequest.toString()).put("inquiryId", inquiry.toString())
                .put("problemId", problem.toString()).putNull("ticketId").put("source", "GUIDE").put("sourceId", message.toString())
                .put("sourceVersion", 1).put("requestRevision", 2).put("state", "UNRESOLVED").put("resultSummary", "안내")
                .put("confirmationOrigin", "HOPS_USER").put("createdAt", "2026-11-02T00:10:00Z").put("respondedAt", "2026-11-02T00:20:00Z");
        jdbc.update("UPDATE message SET payload=?::jsonb WHERE id=?", guidePayload.toString(), message);
        // 다른 문제의 안내 확인은 담지 않는다.
        jdbc.update("INSERT INTO inquiry_problem(id,inquiry_id,problem_no,title,problem_status) VALUES (?,?,2,'다른 문제','GUIDED')", otherProblem, inquiry);
        var otherPayload = mapper.createObjectNode();
        otherPayload.putObject("resolutionRequest").put("id", UUID.randomUUID().toString()).put("inquiryId", inquiry.toString())
                .put("problemId", otherProblem.toString()).putNull("ticketId").put("source", "GUIDE").put("sourceId", UUID.randomUUID().toString())
                .put("sourceVersion", 1).put("requestRevision", 1).put("state", "PENDING").put("resultSummary", "안내")
                .put("confirmationOrigin", "HOPS_USER").put("createdAt", "2026-11-02T00:11:00Z").putNull("respondedAt");
        addMessage("AI", "다른 안내", otherPayload.toString());

        // 처리 기록에 대한 첫 확인: 문의자가 미해결로 응답해 티켓이 다시 열린다.
        record("VERIFIED_RESOLUTION");
        var firstRequest = detail().path("activeResolutionRequest").path("id").asString();
        result(command(requester, "/resolution-requests/" + firstRequest + "/responses",
                mapper.createObjectNode().put("requestId", UUID.randomUUID().toString()).put("expectedRequestRevision", 1)
                        .put("decision", "UNRESOLVED")));
        record("VERIFIED_RESOLUTION");
        var secondRequest = detail().path("activeResolutionRequest").path("id").asString();

        var work = curationInput().path("workRecord");
        var history = work.path("resolutionHistory");
        assertThat(work.path("resolutionRequestId").asString()).isEqualTo(secondRequest);
        assertThat(history.size()).isEqualTo(2);
        // 오래된 순: 안내(2026-11-02) 다음 첫 처리 기록 확인(지금)
        assertThat(history.get(0).path("requestId").asString()).isEqualTo(guideRequest.toString());
        assertThat(history.get(0).path("source").asString()).isEqualTo("GUIDE");
        assertThat(history.get(0).path("state").asString()).isEqualTo("UNRESOLVED");
        assertThat(history.get(0).path("respondedAt").asString()).isEqualTo("2026-11-02T00:20:00Z");
        assertThat(history.get(1).path("requestId").asString()).isEqualTo(firstRequest);
        assertThat(history.get(1).path("source").asString()).isEqualTo("WORK_RECORD");
        // 같은 요청의 최신 상태(응답 반영)만 남는다.
        assertThat(history.get(1).path("state").asString()).isEqualTo("UNRESOLVED");
        assertThat(history.valueStream().map(item -> item.path("requestId").asString())).doesNotContain(secondRequest);
        // 이전 처리 기록은 맥락으로도 들어간다.
        assertThat(work.path("relatedRecords").size()).isEqualTo(1);
    }

    @Test
    void 형식이_맞지_않는_옛_확인_이력은_건너뛰고_처리_기록_저장을_막지_않는다() throws Exception {
        jdbc.update("UPDATE message SET payload=?::jsonb WHERE id=?",
                "{\"resolutionRequest\":{\"id\":\"" + UUID.randomUUID() + "\",\"problemId\":\"" + problem + "\",\"state\":\"BROKEN\"}}", message);

        record("VERIFIED_RESOLUTION");

        assertThat(curationInput().path("workRecord").path("resolutionHistory").size()).isZero();
    }

    private void evidence(UUID evidenceId, String scope, String documentScope, boolean approved) throws Exception {
        String content = "가상 안내 본문 " + evidenceId;
        String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        jdbc.update("INSERT INTO knowledge_document(id,title,document_type,it_system_id,access_level,data_split,review_status) VALUES (?,'가상 안내','EMPLOYEE_GUIDE',?,?,'RAG',?)",
                document, system, documentScope, approved ? "APPROVED" : "PENDING");
        jdbc.update("INSERT INTO knowledge_document_version(knowledge_document_id,version_no,content,content_digest,source_metadata,review_status) VALUES (?,1,?,?,?::jsonb,'APPROVED')",
                document, content, digest, "{\"origin\":\"SYNTHETIC\",\"applicability\":\"가상 시연\",\"appliesToSystemVersion\":null}");
        var item = mapper.createArrayNode();
        item.addObject().put("id", evidenceId.toString()).put("documentId", document.toString()).put("documentVersion", 1)
                .put("kind", "GUIDE").put("title", "가상 안내").put("excerpt", content).put("systemId", system.toString())
                .putNull("appliesToSystemVersion").put("applicability", "가상 시연").put("accessScope", scope).put("origin", "SYNTHETIC");
        jdbc.update("UPDATE standard_request SET related_knowledge=?::jsonb WHERE id=?", item.toString(), standard);
    }

    @Test
    void 상담에_쓴_근거는_현재_권한으로_다시_확인된_것만_담는다() throws Exception {
        var evidenceId = UUID.randomUUID();
        evidence(evidenceId, "EMPLOYEE", "EMPLOYEE", true);

        record("VERIFIED_RESOLUTION");

        var consulted = curationInput().path("workRecord").path("consultedKnowledge");
        assertThat(consulted.size()).isEqualTo(1);
        assertThat(consulted.get(0).path("id").asString()).isEqualTo(evidenceId.toString());
        assertThat(consulted.get(0).path("kind").asString()).isEqualTo("GUIDE");
    }

    @Test
    void 승인이_철회된_근거는_상담에_썼더라도_담지_않는다() throws Exception {
        evidence(UUID.randomUUID(), "EMPLOYEE", "EMPLOYEE", false);

        record("VERIFIED_RESOLUTION");

        assertThat(curationInput().path("workRecord").path("consultedKnowledge").size()).isZero();
    }

    @Test
    void 안내_때와_티켓_생성_때_요청서의_근거를_최신_순으로_합치고_같은_근거는_한_번만_담는다() throws Exception {
        var guideEvidence = UUID.randomUUID();
        evidence(guideEvidence, "EMPLOYEE", "EMPLOYEE", true);
        // 더 나중 입력 버전의 요청서(티켓 생성 때)가 같은 근거를 다시 싣는다.
        var later = UUID.randomUUID();
        jdbc.update("INSERT INTO standard_request(id,inquiry_problem_id,inquiry_version,agent_run_id,intent,work_type,reported_system_id,responsible_system_id,summary,related_knowledge) "
                + "SELECT ?,inquiry_problem_id,2,agent_run_id,intent,work_type,reported_system_id,responsible_system_id,summary,related_knowledge FROM standard_request WHERE id=?",
                later, standard);

        record("VERIFIED_RESOLUTION");

        var consulted = curationInput().path("workRecord").path("consultedKnowledge");
        assertThat(consulted.size()).isEqualTo(1);
        assertThat(consulted.get(0).path("id").asString()).isEqualTo(guideEvidence.toString());
    }

    @Test
    void 맥락이_없는_티켓도_이전_형식과_같게_빈_목록으로_보낸다() throws Exception {
        record("VERIFIED_RESOLUTION");

        var work = curationInput().path("workRecord");
        assertThat(work.path("relatedRecords").isArray()).isTrue();
        assertThat(work.path("relatedRecords").size()).isZero();
        assertThat(work.path("resolutionHistory").size()).isZero();
        assertThat(work.path("consultedKnowledge").size()).isZero();
        assertThat(work.path("linkCandidates").isArray()).isTrue();
        assertThat(work.path("linkCandidates").size()).isZero();
        // 원 기록 필드와 권한 문맥은 그대로다.
        assertThat(curationInput().path("accessContext").path("purpose").asString()).isEqualTo("CASE_DRAFT");
        assertThat(ids(curationInput().path("accessContext").path("allowedDocumentScopes"))).containsExactly("EMPLOYEE", "SYSTEM_STAFF");
    }

    @Test
    void 연결_후보는_이_시스템의_승인된_증상과_확인_항목만_종류와_이름과_함께_담는다() throws Exception {
        rows = new CurationRelationRows(jdbc, system);
        var symptom = rows.symptom(system, "sym-a", "첨부파일이 열리지 않음", "EMPLOYEE", "APPROVED");
        var staffSymptom = rows.symptom(system, "sym-b", "담당자만 보는 증상", "STAFF", "APPROVED");
        var pendingSymptom = rows.symptom(system, "sym-c", "미승인 증상", "EMPLOYEE", "PENDING");
        var otherSystemSymptom = rows.symptom(otherSystem, "sym-d", "다른 시스템 증상", "EMPLOYEE", "APPROVED");
        var disclosable = rows.check(system, "chk-a", "첨부 권한 확인", true, "EMPLOYEE", "APPROVED");
        var staffCheck = rows.check(system, "chk-b", "서버 로그 확인", false, "STAFF", "APPROVED");
        var pendingCheck = rows.check(system, "chk-c", "미승인 확인", true, "EMPLOYEE", "PENDING");
        var otherSystemCheck = rows.check(otherSystem, "chk-d", "다른 시스템 확인", true, "EMPLOYEE", "APPROVED");

        record("VERIFIED_RESOLUTION");

        var candidates = curationInput().path("workRecord").path("linkCandidates");
        var byId = new java.util.LinkedHashMap<String, JsonNode>();
        candidates.forEach(item -> byId.put(item.path("id").asString(), item));
        assertThat(byId.keySet()).containsExactlyInAnyOrder(symptom.toString(), staffSymptom.toString(),
                disclosable.toString(), staffCheck.toString());
        assertThat(byId).doesNotContainKeys(pendingSymptom.toString(), otherSystemSymptom.toString(),
                pendingCheck.toString(), otherSystemCheck.toString());
        assertThat(byId.get(symptom.toString()).path("kind").asString()).isEqualTo("SYMPTOM");
        assertThat(byId.get(symptom.toString()).path("label").asString()).isEqualTo("첨부파일이 열리지 않음");
        assertThat(byId.get(staffCheck.toString()).path("kind").asString()).isEqualTo("CHECK_ITEM");
        byId.values().forEach(item -> assertThat(item.path("systemId").asString()).isEqualTo(system.toString()));
    }

    @Test
    void 연결_후보는_종류별_상한까지만_담는다() throws Exception {
        rows = new CurationRelationRows(jdbc, system);
        for (int index = 0; index < 33; index++) {
            rows.symptom(system, String.format("sym-%02d", index), "증상 " + index, "EMPLOYEE", "APPROVED");
        }
        for (int index = 0; index < 43; index++) {
            rows.check(system, String.format("chk-%02d", index), "확인 " + index, true, "EMPLOYEE", "APPROVED");
        }

        record("VERIFIED_RESOLUTION");

        var candidates = curationInput().path("workRecord").path("linkCandidates");
        assertThat(candidates.valueStream().filter(item -> "SYMPTOM".equals(item.path("kind").asString())).count()).isEqualTo(30);
        assertThat(candidates.valueStream().filter(item -> "CHECK_ITEM".equals(item.path("kind").asString())).count()).isEqualTo(40);
    }

    /** 문서(와 승인된 버전)를 만들고 표준 요청서에 담을 근거를 돌려준다. systemId가 null이면 시스템 없는 공통 문서다. */
    private tools.jackson.databind.node.ObjectNode document(UUID evidenceId, UUID systemId, String type, String level,
            String kind, String scope) throws Exception {
        UUID id = UUID.randomUUID();
        extraDocuments.add(id);
        String content = "가상 본문 " + id;
        String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        jdbc.update("INSERT INTO knowledge_document(id,title,document_type,it_system_id,access_level,data_split,review_status) VALUES (?,'가상 문서',?,?,?,'RAG','APPROVED')",
                id, type, systemId, level);
        jdbc.update("INSERT INTO knowledge_document_version(knowledge_document_id,version_no,content,content_digest,source_metadata,review_status) VALUES (?,1,?,?,?::jsonb,'APPROVED')",
                id, content, digest, "{\"origin\":\"SYNTHETIC\",\"applicability\":\"가상 시연\",\"appliesToSystemVersion\":null}");
        var item = mapper.createObjectNode();
        item.put("id", evidenceId.toString()).put("documentId", id.toString()).put("documentVersion", 1)
                .put("kind", kind).put("title", "가상 문서").put("excerpt", content);
        if (systemId == null) item.putNull("systemId"); else item.put("systemId", systemId.toString());
        item.putNull("appliesToSystemVersion").put("applicability", "가상 시연").put("accessScope", scope).put("origin", "SYNTHETIC");
        return item;
    }

    private void useKnowledge(tools.jackson.databind.node.ObjectNode... items) {
        var array = mapper.createArrayNode();
        for (var item : items) array.add(item);
        jdbc.update("UPDATE standard_request SET related_knowledge=?::jsonb WHERE id=?", array.toString(), standard);
    }

    @Test
    void 직원_공개_담당자_전용_공통_정책_근거는_책임_시스템_범위로_유지하고_다른_시스템_근거와_그_인용만_뺀다() throws Exception {
        var ownGuide = UUID.randomUUID();
        var ownRunbook = UUID.randomUUID();
        var commonPolicy = UUID.randomUUID();
        var otherGuide = UUID.randomUUID();
        useKnowledge(
                document(ownGuide, system, "EMPLOYEE_GUIDE", "EMPLOYEE", "GUIDE", "EMPLOYEE"),
                document(ownRunbook, system, "STAFF_RUNBOOK", "STAFF", "RUNBOOK", "SYSTEM_STAFF"),
                document(commonPolicy, null, "POLICY", "EMPLOYEE", "POLICY", "EMPLOYEE"),
                document(otherGuide, otherSystem, "EMPLOYEE_GUIDE", "EMPLOYEE", "GUIDE", "EMPLOYEE"));
        // 다른 시스템 근거만 인용한 사실과 유지되는 근거를 인용한 사실
        var facts = mapper.createArrayNode();
        facts.addObject().put("text", "다른 시스템 안내에서 확인").putArray("sourceMessageIds");
        ((tools.jackson.databind.node.ObjectNode) facts.get(0)).putArray("knowledgeEvidenceIds").add(otherGuide.toString());
        facts.addObject().put("text", "책임 시스템 안내에서 확인").putArray("sourceMessageIds");
        ((tools.jackson.databind.node.ObjectNode) facts.get(1)).putArray("knowledgeEvidenceIds").add(ownGuide.toString());
        jdbc.update("UPDATE standard_request SET confirmed_facts=?::jsonb WHERE id=?", facts.toString(), standard);

        record("VERIFIED_RESOLUTION");

        var input = curationInput();
        var request = input.path("problems").get(0).path("standardRequest");
        assertThat(ids(request.path("knowledge").valueStream().map(item -> item.path("id")).collect(
                mapper::createArrayNode, (array, node) -> array.add(node), (a, b) -> a.addAll(b))))
                .containsExactlyInAnyOrder(ownGuide.toString(), ownRunbook.toString(), commonPolicy.toString());
        assertThat(request.path("confirmedFacts").size()).isEqualTo(1);
        assertThat(request.path("confirmedFacts").get(0).path("text").asString()).isEqualTo("책임 시스템 안내에서 확인");
        var consulted = input.path("workRecord").path("consultedKnowledge");
        assertThat(ids(consulted.valueStream().map(item -> item.path("id")).collect(
                mapper::createArrayNode, (array, node) -> array.add(node), (a, b) -> a.addAll(b))))
                .containsExactlyInAnyOrder(ownGuide.toString(), ownRunbook.toString(), commonPolicy.toString());
        assertThat(ids(input.path("accessContext").path("allowedDocumentScopes"))).containsExactly("EMPLOYEE", "SYSTEM_STAFF");
        assertThat(ids(input.path("accessContext").path("systemIds"))).containsExactly(system.toString());
    }

    @Test
    void 범위_밖_근거를_가리키는_책임_판단_근거도_입력에서_뺀다() throws Exception {
        var otherGuide = UUID.randomUUID();
        var ownGuide = UUID.randomUUID();
        useKnowledge(document(otherGuide, otherSystem, "EMPLOYEE_GUIDE", "EMPLOYEE", "GUIDE", "EMPLOYEE"),
                document(ownGuide, system, "EMPLOYEE_GUIDE", "EMPLOYEE", "GUIDE", "EMPLOYEE"));
        var candidates = mapper.createArrayNode();
        candidates.addObject().put("systemId", system.toString()).putArray("evidenceIds")
                .add(otherGuide.toString()).add(ownGuide.toString());
        jdbc.update("UPDATE standard_request SET system_candidates=?::jsonb, responsible_system_id=? WHERE id=?", candidates.toString(), system, standard);

        record("VERIFIED_RESOLUTION");

        var evidenceIds = curationInput().path("problems").get(0).path("standardRequest").path("responsibilityEvidenceIds");
        assertThat(ids(evidenceIds)).containsExactly(ownGuide.toString());
    }
}
