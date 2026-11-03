package com.hihops.hops.ticket.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 저장 JSONB의 검증 경계. 읽지 못한 데이터를 빈 정상 결과로 바꾸지 않는다. */
@Component
@RequiredArgsConstructor
public class TicketStoredData {
    private final ObjectMapper mapper;

    public JsonNode parse(String stored) {
        try {
            JsonNode result = mapper.readTree(stored);
            if (result == null || result.isNull()) throw new IllegalArgumentException();
            return result;
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    public <T> T value(JsonNode node, Class<T> type) {
        try {
            return mapper.treeToValue(node, type);
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    public <T> List<T> list(JsonNode node, Class<T> type) {
        if (!node.isArray()) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        List<T> result = new ArrayList<>();
        for (JsonNode item : node) {
            T value = value(item, type);
            if (value == null || value instanceof String text && text.isBlank()) {
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
            }
            result.add(value);
        }
        return List.copyOf(result);
    }

    public <T> List<T> list(String stored, Class<T> type) {
        return list(parse(stored), type);
    }
}
