package com.hihops.hops.agent.service;

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

    public void requireTextOnly(AgentRun run) {
        JsonNode input = read(run.getInputSnapshot());
        if (!input.isObject()) throw unavailable();
        JsonNode messages = input.path("messages");
        if (!messages.isMissingNode()) {
            if (!messages.isArray()) throw unavailable();
            for (JsonNode message : messages) {
                if (!message.isObject()) throw unavailable();
                JsonNode images = message.path("imageAttachments");
                if (!images.isMissingNode() && (!images.isArray() || !images.isEmpty())) throw unavailable();
            }
        }
        if (run.getResult() != null) {
            JsonNode result = read(run.getResult());
            if (!result.isObject()) throw unavailable();
            JsonNode observations = result.path("imageObservations");
            if (!observations.isMissingNode() && (!observations.isArray() || !observations.isEmpty())) throw unavailable();
        }
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
