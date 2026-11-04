package com.hihops.hops.inquiry.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.hihops.hops.global.idempotency.IdempotencyService;

import tools.jackson.databind.json.JsonMapper;

/**
 * USER 메시지 payload JSON (docs/DATA.md 11.3). 모든 키를 두고, 해당 없는 값은 null·빈 목록이다.
 *
 * <p>problemLinks는 사용자가 고른 문제를 메시지 전체 구간 [0, 코드 포인트 길이)에 연결한다. 담당자 화면은 spans가 있는 구간만 보여 주므로
 * 비워 두지 않는다. 대상을 고르지 않으면 빈 목록이고 분석이 대상을 정한다.
 */
final class UserMessagePayload {

    private UserMessagePayload() {
    }

    static String json(JsonMapper jsonMapper, UUID requestId, @Nullable UUID reportedSystemId, List<UUID> problemIds,
            String content) {
        List<Map<String, Integer>> wholeMessage = List.of(Map.of("start", 0, "end", content.codePointCount(0, content.length())));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractVersion", IdempotencyService.CONTRACT_VERSION);
        payload.put("requestId", requestId);
        payload.put("answerRunId", null);
        payload.put("problemLinks", problemIds.stream()
                .map(problemId -> Map.of("problemId", problemId, "revision", 1, "spans", wholeMessage))
                .toList());
        payload.put("questionBundles", List.of());
        payload.put("guide", null);
        payload.put("pendingTarget", false);
        payload.put("reportedSystemId", reportedSystemId);
        return jsonMapper.writeValueAsString(payload);
    }
}
