package com.hihops.hops.inquiry.service;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.hihops.hops.agent.service.AgentResultRejectedException;
import com.hihops.hops.inquiry.domain.Inquiry;
import com.hihops.hops.inquiry.domain.Message;
import com.hihops.hops.inquiry.domain.MessageStatus;
import com.hihops.hops.inquiry.domain.SenderType;
import com.hihops.hops.inquiry.repository.MessageRepository;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 사실이 인용한 사용자 메시지를 그 문제에 잇는다 (message.payload.problemLinks, docs/DATA.md 11.3).
 *
 * <p>문의자·담당자 투영({@code RelatedMessageProjector})과 티켓 도구의 출처 검증({@code TicketCreationValidator})은 문제에 연결된
 * 메시지만 그 문제의 출처로 인정한다. 연결이 없으면 다음 실행 입력의 확인 사실이 출처를 잃고 걸러지며, 안내 뒤 미해결로 재분석한
 * 문제에 AI가 티켓을 만들 때 출처 검증에서 거부된다. 문제를 저장하는 결과 반영 거래 안에서 부른다.
 */
@Component
@RequiredArgsConstructor
public class MessageProblemLinker {

    private final MessageRepository messages;
    private final JsonMapper mapper;

    /**
     * 사용자 메시지 전체를 문제에 잇는다. 같은 차수의 연결이 이미 있으면 건드리지 않는다. 겹치는 구간을 만들지 않으려고
     * 메시지 전체를 잇는다.
     *
     * @throws AgentResultRejectedException 이 문의의 저장이 끝난 사용자 메시지가 아닐 때 (INVALID_OUTPUT)
     */
    public void link(Inquiry inquiry, UUID messageId, UUID problemId) {
        Message message = messages.findById(messageId).orElseThrow(MessageProblemLinker::invalid);
        if (!message.getInquiry().getId().equals(inquiry.getId()) || message.isDeleted()
                || message.getSenderType() != SenderType.USER || message.getMessageStatus() != MessageStatus.COMPLETED) {
            throw invalid();
        }
        ObjectNode payload = (ObjectNode) mapper.readTree(message.getPayload());
        ArrayNode links = payload.path("problemLinks").isArray() ? (ArrayNode) payload.get("problemLinks")
                : payload.putArray("problemLinks");
        for (var link : links) {
            // revision이 없는 예전 연결은 모든 차수에 걸린 연결이다 (MessagePayload.problemIds, TicketCreationService)
            boolean sameRevision = link.path("revision").isMissingNode() || link.path("revision").isNull()
                    || link.path("revision").asInt(-1) == message.getRevision();
            if (problemId.toString().equals(link.path("problemId").asString()) && sameRevision) {
                return;
            }
        }
        int length = message.getContent().codePointCount(0, message.getContent().length());
        ObjectNode link = links.addObject().put("problemId", problemId.toString()).put("revision", message.getRevision());
        link.putArray("spans").addObject().put("start", 0).put("end", length);
        message.updatePayload(mapper.writeValueAsString(payload));
    }

    private static AgentResultRejectedException invalid() {
        return new AgentResultRejectedException("INVALID_OUTPUT", "사실의 출처 메시지를 문제에 연결할 수 없습니다");
    }
}
