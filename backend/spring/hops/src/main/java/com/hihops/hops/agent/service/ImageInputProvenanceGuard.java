package com.hihops.hops.agent.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.inquiry.domain.Message;
import com.hihops.hops.inquiry.domain.SenderType;
import com.hihops.hops.inquiry.repository.MessageRepository;
import com.hihops.hops.ticket.service.RelatedMessageProjector;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

/** 이미지 출처 전파가 준비될 때까지 이미지 없는 실행이 이미지 유래 답변을 소비하지 않게 한다. */
@Component
@RequiredArgsConstructor
class ImageInputProvenanceGuard {
    private final RelatedMessageProjector projector;
    private final MessageRepository messages;

    void requireTextSafe(Collection<Message> input) {
        if (!projector.restricted(input).isEmpty()) throw unavailable();
    }

    void requireRetrySafe(JsonNode snapshot, UUID inquiryId) {
        JsonNode input = snapshot.path("messages");
        if (!input.isArray()) throw unavailable();
        var aiIds = new HashSet<UUID>();
        boolean hasImages = false;
        for (JsonNode message : input) {
            if (!message.isObject()) throw unavailable();
            JsonNode images = message.path("imageAttachments");
            if (!images.isMissingNode()) {
                if (!images.isArray()) throw unavailable();
                hasImages |= !images.isEmpty();
            }
            String sender = message.path("sender").asString("");
            if ("AI".equals(sender)) {
                try {
                    if (!aiIds.add(UUID.fromString(message.path("id").asString("")))) throw unavailable();
                } catch (IllegalArgumentException exception) {
                    throw unavailable();
                }
            } else if (!"USER".equals(sender)) {
                throw unavailable();
            }
        }
        // 이미지가 있으면 기존 이미지 실행 차단이 이 실행 전체에 적용된다.
        if (hasImages || aiIds.isEmpty()) return;
        var found = new ArrayList<Message>();
        messages.findAllById(aiIds).forEach(found::add);
        if (found.size() != aiIds.size() || found.stream().anyMatch(message -> message.isDeleted()
                || message.getSenderType() != SenderType.AI || !message.getInquiry().getId().equals(inquiryId))) {
            throw unavailable();
        }
        requireTextSafe(found);
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "이전 이미지 분석의 출처를 확인할 수 없습니다.");
    }
}
