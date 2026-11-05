package com.hihops.hops.agent.internal;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;

import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AI→Spring 지식 검색. 인증은 /internal/v2/** 서비스 토큰 chain이 맡는다.
 *
 * <p>요청 본문은 알려진 필드만 받는다. Spring의 기본 JSON 설정은 알 수 없는 필드를 조용히 버리므로
 * {@code accessScope}·{@code purpose} 같은 범위 확대 시도가 무시된 채 성공하지 않도록 본문을 직접 확인한다.
 */
@RestController
@RequestMapping("/internal/v2/runs/{runId}/knowledge-searches")
@RequiredArgsConstructor
public class KnowledgeSearchController {
    private static final Set<String> FIELDS = Arrays.stream(KnowledgeSearchRequest.class.getRecordComponents())
            .map(component -> component.getName()).collect(Collectors.toUnmodifiableSet());

    private final KnowledgeSearchHandler handler;
    private final JsonMapper mapper;
    private final Validator validator;

    @PostMapping
    public KnowledgeSearchResponse search(@PathVariable UUID runId, @RequestBody JsonNode body) {
        return handler.search(runId, parse(body));
    }

    private KnowledgeSearchRequest parse(JsonNode body) {
        if (body == null || !body.isObject() || !FIELDS.containsAll(body.propertyNames())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        KnowledgeSearchRequest request;
        try {
            request = mapper.treeToValue(body, KnowledgeSearchRequest.class);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        if (request == null || !validator.validate(request).isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        return request;
    }
}
