package com.hihops.hops.inquiry.service;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
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
 * 볼 수 있는 근거가 없으면 채팅 본문의 안내 부분과 안내 해결 확인 요약을 {@link #HIDDEN_NOTICE}로 바꾼다. 저장된 메시지는 바꾸지 않는다.
 * 트랜잭션 안에서 부른다.
 */
@Component
@RequiredArgsConstructor
public class GuideVisibility {

    static final String HIDDEN_NOTICE = "이 안내의 근거 문서를 지금은 볼 수 없어 안내 내용을 숨겼어요. 필요하면 담당자에게 문의해 주세요.";

    private final AgentRunRepository agentRunRepository;
    private final TicketKnowledgeReader knowledgeReader;
    private final JsonMapper jsonMapper;

    /** 안내 메시지(payload.guide)의 근거 중 지금 문의자가 볼 수 있는 직원용 근거. 안내가 없는 메시지면 빈 목록이다. */
    public List<KnowledgeEvidenceResponse> visibleEvidence(Inquiry inquiry, Message message) {
        JsonNode payload = jsonMapper.readTree(message.getPayload());
        Set<String> cited = new HashSet<>();
        payload.path("guide").path("evidenceIds").forEach(id -> cited.add(id.asString("")));
        if (cited.isEmpty()) {
            return List.of();
        }
        // 근거 원본은 그 답변을 만든 실행의 결과(AiResult.evidence)에 있다
        ArrayNode evidence = jsonMapper.createArrayNode();
        String runId = payload.path("answerRunId").asString("");
        Optional<AgentRun> run = runId.isEmpty() ? Optional.empty() : agentRunRepository.findById(UUID.fromString(runId));
        if (run.isPresent() && run.get().getResult() != null) {
            for (JsonNode item : jsonMapper.readTree(run.get().getResult()).path("evidence")) {
                if (cited.contains(item.path("id").asString(""))) {
                    evidence.add(item);
                }
            }
        }
        return knowledgeReader.visible(evidence.toString(), inquiry.getRequester()).stream()
                .filter(item -> "EMPLOYEE".equals(item.accessScope())).toList();
    }

    /** 안내가 있는 AI 메시지인데 지금 볼 수 있는 근거가 하나도 없는지. */
    public boolean hidden(Inquiry inquiry, Message message) {
        return message.getSenderType() == SenderType.AI && !guideText(message).isEmpty()
                && visibleEvidence(inquiry, message).isEmpty();
    }

    /** 문의자에게 보여 줄 메시지 본문. 근거를 볼 수 없는 안내 부분만 숨김 안내로 바꾼다. */
    public String contentFor(Inquiry inquiry, Message message) {
        if (!hidden(inquiry, message)) {
            return message.getContent();
        }
        return message.getContent().replace(guideText(message), HIDDEN_NOTICE);
    }

    /** 안내 해결 확인 요청. 근거를 볼 수 없으면 요약(안내 문구)을 숨김 안내로 바꾼다. */
    public ResolutionRequestResponse resolutionFor(Inquiry inquiry, Message message, ResolutionRequestResponse request) {
        if (!hidden(inquiry, message)) {
            return request;
        }
        return new ResolutionRequestResponse(request.id(), request.inquiryId(), request.problemId(), request.ticketId(),
                request.source(), request.sourceId(), request.sourceVersion(), request.requestRevision(), request.state(),
                HIDDEN_NOTICE, request.confirmationOrigin(), request.createdAt(), request.respondedAt());
    }

    private String guideText(Message message) {
        return jsonMapper.readTree(message.getPayload()).path("guide").path("text").asString("");
    }
}
