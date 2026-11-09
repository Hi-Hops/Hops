package com.hihops.hops.agent.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.springframework.stereotype.Component;

import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 이미지 유래 텍스트의 문제별 공개 계약이 준비될 때까지 업무 저장·담당자 노출을 막는다. */
@Component
@RequiredArgsConstructor
public class ImageRunSafety {
    private final JsonMapper mapper;
    private final ImageInputProvenanceGuard imageProvenance;

    public void requireTextOnly(AgentRun run) {
        requireTextOnly(List.of(run));
    }

    /** 한 목록의 출처를 모아 검사해 티켓마다 별도 조회하지 않는다. */
    public void requireTextOnly(Collection<AgentRun> runs) {
        var checks = new ArrayList<ImageInputProvenanceGuard.SnapshotCheck>();
        for (AgentRun run : runs) {
            var input = inspectDirectInput(run);
            if (input != null) checks.add(new ImageInputProvenanceGuard.SnapshotCheck(input, run.getInquiry().getId()));
        }
        if (!checks.isEmpty() && !imageProvenance.areRetrySafe(checks)) throw unavailable();
    }

    private @org.jspecify.annotations.Nullable JsonNode inspectDirectInput(AgentRun run) {
        JsonNode input = read(run.getInputSnapshot());
        if (!input.isObject()) throw unavailable();
        JsonNode messages = input.path("messages");
        boolean hasAiInput = false;
        if (!messages.isMissingNode()) {
            if (!messages.isArray()) throw unavailable();
            for (JsonNode message : messages) {
                if (!message.isObject()) throw unavailable();
                JsonNode images = message.path("imageAttachments");
                if (!images.isMissingNode() && (!images.isArray() || !images.isEmpty())) throw unavailable();
                hasAiInput |= "AI".equals(message.path("sender").asString(""));
            }
        }
        if (run.getResult() != null) {
            JsonNode result = read(run.getResult());
            if (!result.isObject()) throw unavailable();
            JsonNode observations = result.path("imageObservations");
            if (!observations.isMissingNode() && (!observations.isArray() || !observations.isEmpty())) throw unavailable();
        }
        return hasAiInput ? input : null;
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (JacksonException exception) {
            throw unavailable();
        }
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "이미지 분석 정보의 공개 범위를 확인할 수 없습니다.");
    }
}
