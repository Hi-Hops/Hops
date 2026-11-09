package com.hihops.hops.agent.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    private static final int BATCH_SIZE = 500;
    private static final int MAX_MESSAGES = 5000;
    private final RelatedMessageProjector projector;
    private final MessageRepository messages;

    void requireTextSafe(Collection<Message> input) {
        if (!projector.restricted(input).isEmpty()) throw unavailable();
    }

    void requireRetrySafe(JsonNode snapshot, UUID inquiryId) {
        if (!isRetrySafe(snapshot, inquiryId)) throw unavailable();
    }

    boolean isRetrySafe(JsonNode snapshot, UUID inquiryId) {
        return areRetrySafe(List.of(new SnapshotCheck(snapshot, inquiryId)));
    }

    boolean areRetrySafe(Collection<SnapshotCheck> snapshots) {
        Map<UUID, UUID> expected = new HashMap<>();
        for (SnapshotCheck check : snapshots) {
            var ids = aiInputs(check.snapshot());
            if (ids == null) return false;
            for (UUID id : ids) {
                UUID existing = expected.putIfAbsent(id, check.inquiryId());
                if (existing != null && !existing.equals(check.inquiryId())) return false;
            }
            if (expected.size() > MAX_MESSAGES) return false;
        }
        if (expected.isEmpty()) return true;
        var found = new ArrayList<Message>();
        var ordered = new ArrayList<>(expected.keySet());
        for (int offset = 0; offset < ordered.size(); offset += BATCH_SIZE) {
            messages.findAllById(ordered.subList(offset, Math.min(offset + BATCH_SIZE, ordered.size())))
                    .forEach(found::add);
        }
        if (found.size() != expected.size() || found.stream().anyMatch(message -> message.isDeleted()
                || message.getSenderType() != SenderType.AI
                || !message.getInquiry().getId().equals(expected.get(message.getId())))) return false;
        return projector.restricted(found).isEmpty();
    }

    /** null은 손상된 입력, 빈 목록은 이미지가 있거나 AI 입력이 없는 실행이다. */
    private static @org.jspecify.annotations.Nullable HashSet<UUID> aiInputs(JsonNode snapshot) {
        JsonNode input = snapshot.path("messages");
        if (!input.isArray()) return null;
        var aiIds = new HashSet<UUID>();
        boolean hasImages = false;
        for (JsonNode message : input) {
            if (!message.isObject()) return null;
            JsonNode images = message.path("imageAttachments");
            if (!images.isMissingNode()) {
                if (!images.isArray()) return null;
                hasImages |= !images.isEmpty();
            }
            String sender = message.path("sender").asString("");
            if ("AI".equals(sender)) {
                try {
                    if (!aiIds.add(UUID.fromString(message.path("id").asString("")))) return null;
                    if (aiIds.size() > MAX_MESSAGES) return null;
                } catch (IllegalArgumentException exception) {
                    return null;
                }
            } else if (!"USER".equals(sender)) {
                return null;
            }
        }
        // 이미지가 있으면 기존 이미지 실행 차단이 이 실행 전체에 적용된다.
        return hasImages ? new HashSet<>() : aiIds;
    }

    record SnapshotCheck(JsonNode snapshot, UUID inquiryId) { }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "이전 이미지 분석의 출처를 확인할 수 없습니다.");
    }
}
