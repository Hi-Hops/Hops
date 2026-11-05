package com.hihops.hops.agent.internal;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;

import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 내부 읽기 API의 요청 본문을 record로 바꾼다. Spring의 기본 JSON 설정은 알 수 없는 필드를 조용히 버리므로
 * {@code accessScope}·{@code purpose} 같은 범위 확대 시도가 무시된 채 성공하지 않도록, record에 없는 필드는 400으로 거부한다.
 * 형식·검증 위반도 400 INVALID_INPUT이다.
 */
@Component
@RequiredArgsConstructor
class StrictRequestParser {
    private final JsonMapper mapper;
    private final Validator validator;

    <T extends Record> T parse(JsonNode body, Class<T> type) {
        Set<String> fields = Arrays.stream(type.getRecordComponents()).map(component -> component.getName())
                .collect(Collectors.toUnmodifiableSet());
        if (body == null || !body.isObject() || !fields.containsAll(body.propertyNames())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        T request;
        try {
            request = mapper.treeToValue(body, type);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        if (request == null || !validator.validate(request).isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        return request;
    }
}
