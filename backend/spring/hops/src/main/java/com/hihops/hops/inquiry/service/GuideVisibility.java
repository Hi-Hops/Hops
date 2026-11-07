package com.hihops.hops.inquiry.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.agent.repository.AgentRunRepository;
import com.hihops.hops.inquiry.domain.Inquiry;
import com.hihops.hops.inquiry.domain.Message;
import com.hihops.hops.inquiry.domain.SenderType;
import com.hihops.hops.ticket.dto.ResolutionRequestResponse;
import com.hihops.hops.ticket.dto.StandardRequestResponse.KnowledgeEvidenceResponse;
import com.hihops.hops.ticket.service.TicketKnowledgeReader;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * 문의자에게 보여 줄 안내가 지금도 볼 수 있는 근거에서 나왔는지 조회할 때마다 다시 확인한다 (SEC-09).
 *
 * <p>안내를 저장한 뒤 근거 문서가 삭제·승인 취소·새 버전 교체·담당자 전용으로 바뀌면 저장된 안내 문구도 더는 공개 근거가 없다.
 * 근거는 저장 당시 결과가 아니라 {@link TicketKnowledgeReader#visible}로 지금 문의자 권한에서 다시 거른 직원용(EMPLOYEE) 근거만 쓴다.
 * 안내가 인용한 근거 중 <b>하나라도</b> 지금 볼 수 없으면 숨긴다. 안내 문단은 인용한 근거 어느 것에서든 올 수 있어서다.
 * 숨길 때는 채팅 본문의 안내 부분과 안내 해결 확인 요약을 {@link #HIDDEN_NOTICE}로 바꾼다. 본문에서 안내 부분을 글자 그대로 찾지 못하면
 * 본문 전체를 바꾼다(새지 않는 쪽). 저장된 메시지는 바꾸지 않는다. 트랜잭션 안에서 부른다.
 *
 * <p>메시지 payload와 실행 결과는 메시지마다 한 번만 읽는다. 대화 한 페이지는 {@link #viewsOf}로 실행 결과를 한 번에 조회한다.
 */
@Component
@RequiredArgsConstructor
public class GuideVisibility {

    static final String HIDDEN_NOTICE = "이 안내의 근거 문서를 지금은 볼 수 없어 안내 내용을 숨겼어요. 필요하면 담당자에게 문의해 주세요.";

    private final AgentRunRepository agentRunRepository;
    private final TicketKnowledgeReader knowledgeReader;
    private final JsonMapper jsonMapper;

    /**
     * 한 안내 메시지를 문의자에게 보여 줄 방법.
     *
     * @param text     저장된 안내 문구(payload.guide.text). 안내가 없는 메시지면 빈 문자열
     * @param evidence 지금 문의자가 볼 수 있는 인용 근거
     * @param hidden   안내가 있고 인용 근거 중 하나라도 지금 볼 수 없는지
     */
    public record GuideView(String text, List<KnowledgeEvidenceResponse> evidence, boolean hidden) {

        static final GuideView NONE = new GuideView("", List.of(), false);

        /** 문의자에게 보여 줄 메시지 본문. */
        public String contentOf(Message message) {
            String content = message.getContent();
            if (!hidden) {
                return content;
            }
            return !text.isEmpty() && content.contains(text) ? content.replace(text, HIDDEN_NOTICE) : HIDDEN_NOTICE;
        }

        /** 이 안내의 해결 확인 요청. 숨기면 요약(안내 문구)을 숨김 안내로 바꾼다. */
        public ResolutionRequestResponse resolutionOf(ResolutionRequestResponse request) {
            if (!hidden) {
                return request;
            }
            return new ResolutionRequestResponse(request.id(), request.inquiryId(), request.problemId(), request.ticketId(),
                    request.source(), request.sourceId(), request.sourceVersion(), request.requestRevision(), request.state(),
                    HIDDEN_NOTICE, request.confirmationOrigin(), request.createdAt(), request.respondedAt());
        }
    }

    /** 메시지 하나의 보기. */
    public GuideView viewOf(Inquiry inquiry, Message message) {
        return viewsOf(inquiry, List.of(message)).get(message.getId());
    }

    /** 메시지 여러 개의 보기(메시지 ID → 보기). 안내가 있는 AI 메시지의 실행 결과를 한 번에 조회한다. */
    public Map<UUID, GuideView> viewsOf(Inquiry inquiry, Collection<Message> messages) {
        Map<UUID, GuideView> views = new HashMap<>();
        Map<UUID, JsonNode> guides = new HashMap<>();
        Set<UUID> runIds = new LinkedHashSet<>();
        for (Message message : messages) {
            views.put(message.getId(), GuideView.NONE);
            if (message.getSenderType() != SenderType.AI) {
                continue;
            }
            JsonNode payload = jsonMapper.readTree(message.getPayload());
            if (payload.path("guide").isObject()) {
                guides.put(message.getId(), payload);
                String runId = payload.path("answerRunId").asString("");
                if (!runId.isEmpty()) {
                    runIds.add(UUID.fromString(runId));
                }
            }
        }
        if (guides.isEmpty()) {
            return views;
        }
        Map<UUID, JsonNode> results = new HashMap<>();
        for (AgentRun run : agentRunRepository.findAllById(runIds)) {
            if (run.getResult() != null) {
                results.put(run.getId(), jsonMapper.readTree(run.getResult()));
            }
        }
        guides.forEach((messageId, payload) -> views.put(messageId, viewOf(inquiry, payload, results)));
        return views;
    }

    private GuideView viewOf(Inquiry inquiry, JsonNode payload, Map<UUID, JsonNode> results) {
        JsonNode guide = payload.path("guide");
        String text = guide.path("text").asString("");
        Set<String> cited = new HashSet<>();
        guide.path("evidenceIds").forEach(id -> cited.add(id.asString("")));
        // 근거 원본은 그 답변을 만든 실행의 결과(AiResult.evidence)에 있다
        ArrayNode evidence = jsonMapper.createArrayNode();
        String runId = payload.path("answerRunId").asString("");
        JsonNode result = runId.isEmpty() ? null : results.get(UUID.fromString(runId));
        if (result != null) {
            for (JsonNode item : result.path("evidence")) {
                if (cited.contains(item.path("id").asString(""))) {
                    evidence.add(item);
                }
            }
        }
        List<KnowledgeEvidenceResponse> visible = cited.isEmpty() ? List.of()
                : knowledgeReader.visible(evidence.toString(), inquiry.getRequester()).stream()
                        .filter(item -> "EMPLOYEE".equals(item.accessScope())).toList();
        Set<String> visibleIds = new HashSet<>();
        visible.forEach(item -> visibleIds.add(item.id().toString()));
        // 인용 근거가 없거나 하나라도 지금 볼 수 없으면 안내를 보여 주지 않는다
        boolean hidden = cited.isEmpty() || !visibleIds.containsAll(cited);
        return new GuideView(text, visible, hidden);
    }
}
