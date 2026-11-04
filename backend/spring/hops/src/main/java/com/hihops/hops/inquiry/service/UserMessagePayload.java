package com.hihops.hops.inquiry.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.hihops.hops.global.idempotency.IdempotencyService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * USER 메시지 payload JSON (docs/DATA.md 11.3). 모든 키를 두고, 해당 없는 값은 null·빈 목록이다.
 *
 * <p>problemLinks는 사용자가 고른 문제를 메시지 전체 구간 [0, 코드 포인트 길이)에 연결한다. 담당자 화면은 spans가 있는 구간만 보여 주므로
 * 비워 두지 않는다. 대상을 고르지 않으면 빈 목록이고 분석이 대상을 정한다.
 *
 * <p>revisionRequestIds는 정정한 차수마다 그 정정 요청 ID를 남긴다(처음 원문은 requestId). 정정으로 시작한 실행의 오류에
 * 접수 명령 ID를 넣는 데 쓴다 (WIRE_CONTRACT 12.8 끝).
 *
 * <p>pendingTarget은 배정된 문제가 있는데 대상을 고르지 않아 어느 문제의 보완인지 정하지 못한 메시지다 (WIRE_CONTRACT 12.6).
 * problem-links로 대상을 정하면 false가 된다. 대기 메시지는 대상이 정해질 때까지 AI 입력의 대화에서 뺀다.
 *
 * <p>runRequestIds는 이 메시지를 시작점으로 접수한 실행마다 그 실행을 접수한 공개 명령 ID를 남긴다. 실행 오류의 requestId로 쓴다
 * (WIRE_CONTRACT 12.8 끝 "해당 run 접수를 만든 공개 명령 ID"). 같은 메시지·차수에서 여러 경로(전송·정정·대상 확정·멈춘 분석 잇기)가
 * 실행을 만들 수 있어 추론하지 않고 실행마다 기록한다.
 */
final class UserMessagePayload {

    private UserMessagePayload() {
    }

    static String json(JsonMapper jsonMapper, UUID requestId, @Nullable UUID reportedSystemId, List<UUID> problemIds,
            String content) {
        return json(jsonMapper, requestId, reportedSystemId, problemIds, content, false);
    }

    static String json(JsonMapper jsonMapper, UUID requestId, @Nullable UUID reportedSystemId, List<UUID> problemIds,
            String content, boolean pendingTarget) {
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
        payload.put("pendingTarget", pendingTarget);
        payload.put("reportedSystemId", reportedSystemId);
        payload.put("revisionRequestIds", Map.of());
        payload.put("runRequestIds", Map.of());
        return jsonMapper.writeValueAsString(payload);
    }

    /**
     * 정정한 원문의 payload. 처음 requestId와 다른 키는 그대로 두고 두 가지를 더한다.
     * <ol>
     *   <li>이전 원문 전체에 걸린 문제 연결(사용자가 고른 문제)은 새 원문 전체로 잇는다. 담당자 화면은 차수가 맞는 연결만 보여 주므로
     *       잇지 않으면 정정한 메시지가 그 문제의 관련 대화에서 사라진다. 일부 구간에만 걸린 연결은 새 원문에 옮길 수 없어 잇지 않는다.
     *       이전 차수의 연결은 정정 이력 투영을 위해 남긴다.</li>
     *   <li>revisionRequestIds에 새 차수 → 정정 요청 ID를 남긴다.</li>
     * </ol>
     */
    static String revised(JsonMapper jsonMapper, String payload, int previousRevision, String previousContent,
            int revision, String content, UUID revisionRequestId) {
        ObjectNode node = (ObjectNode) jsonMapper.readTree(payload);
        ArrayNode links = node.path("problemLinks").isArray() ? (ArrayNode) node.get("problemLinks")
                : node.putArray("problemLinks");
        int previousLength = previousContent.codePointCount(0, previousContent.length());
        List<String> carried = new ArrayList<>();
        for (JsonNode link : links) {
            String problemId = link.path("problemId").asString("");
            if (link.path("revision").asInt(-1) == previousRevision && coversWhole(link.path("spans"), previousLength)
                    && !carried.contains(problemId)) {
                carried.add(problemId);
            }
        }
        int length = content.codePointCount(0, content.length());
        for (String problemId : carried) {
            ObjectNode link = links.addObject().put("problemId", problemId).put("revision", revision);
            link.putArray("spans").addObject().put("start", 0).put("end", length);
        }
        ObjectNode requestIds = node.path("revisionRequestIds").isObject() ? (ObjectNode) node.get("revisionRequestIds")
                : node.putObject("revisionRequestIds");
        requestIds.put(Integer.toString(revision), revisionRequestId.toString());
        return jsonMapper.writeValueAsString(node);
    }

    /**
     * 대기 메시지의 보완 대상을 확정한 payload. 원문은 바꾸지 않고 현재 차수의 원문 전체를 고른 문제에 잇고 pendingTarget을 푼다.
     */
    static String confirmed(JsonMapper jsonMapper, String payload, int revision, String content, List<UUID> problemIds) {
        ObjectNode node = (ObjectNode) jsonMapper.readTree(payload);
        ArrayNode links = node.path("problemLinks").isArray() ? (ArrayNode) node.get("problemLinks")
                : node.putArray("problemLinks");
        int length = content.codePointCount(0, content.length());
        for (UUID problemId : problemIds) {
            ObjectNode link = links.addObject().put("problemId", problemId.toString()).put("revision", revision);
            link.putArray("spans").addObject().put("start", 0).put("end", length);
        }
        node.put("pendingTarget", false);
        return jsonMapper.writeValueAsString(node);
    }

    /** 이 메시지를 시작점으로 접수한 실행과 그 실행을 접수한 공개 명령 ID를 남긴 payload. */
    static String withRun(JsonMapper jsonMapper, String payload, UUID runId, UUID requestId) {
        ObjectNode node = (ObjectNode) jsonMapper.readTree(payload);
        ObjectNode runs = node.path("runRequestIds").isObject() ? (ObjectNode) node.get("runRequestIds")
                : node.putObject("runRequestIds");
        runs.put(runId.toString(), requestId.toString());
        return jsonMapper.writeValueAsString(node);
    }

    private static boolean coversWhole(JsonNode spans, int length) {
        return spans.isArray() && spans.size() == 1 && spans.get(0).path("start").asInt(-1) == 0
                && spans.get(0).path("end").asInt(-1) == length;
    }
}
