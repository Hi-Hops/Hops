package com.hihops.hops.agent.service;

import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.agent.dto.RunSummaryResponse;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.global.exception.ErrorResponse;
import com.hihops.hops.global.idempotency.IdempotencyService;
import com.hihops.hops.global.idempotency.ResourceRef;
import com.hihops.hops.global.idempotency.ResourceType;
import com.hihops.hops.inquiry.domain.MessagePayload;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 상담 실행을 공개 RunSummary로 바꾼다 (DATA_TYPES 신규 절 RunSummary, WIRE_CONTRACT 12.8 끝).
 *
 * <p>상태: DB CANCELLED는 SUPERSEDED로 보낸다. 오류는 FAILED·TIMED_OUT에만 붙이고, 내부 오류 코드는 공개 코드로 바꾼다.
 * 원래 오류 문구·AI 응답 본문은 내보내지 않는다. 오류의 requestId는 이 실행을 접수한 공개 명령 ID다:
 * 재시도로 만든 실행은 그 재시도 요청, 메시지 명령(전송·정정·보완 대상 확정)이 접수한 실행은 시작 메시지 payload.runRequestIds에
 * 기록한 그 명령, 기록이 없는 예전 실행은 정정 차수의 정정 요청 또는 시작 메시지를 만든 요청이다.
 *
 * <p>트랜잭션 안에서 부른다(시작 메시지를 지연 로딩한다).
 */
@Component
@RequiredArgsConstructor
public class RunSummaryReader {

    private final JsonMapper jsonMapper;
    private final IdempotencyService idempotencyService;

    /**
     * @throws IllegalStateException 상담 실행이 아니거나 입력에 answerMessageId가 없을 때. 저장 규칙이 깨진 것이라 빈 값으로 숨기지 않는다
     */
    public RunSummaryResponse read(AgentRun run) {
        if (run.getTriggerMessage() == null) {
            throw new IllegalStateException("상담 실행이 아니다: " + run.getId());
        }
        JsonNode input = jsonMapper.readTree(run.getInputSnapshot());
        JsonNode answerMessageId = input.path("answerMessageId");
        if (!answerMessageId.isString()) {
            throw new IllegalStateException("실행 입력에 answerMessageId가 없다: " + run.getId());
        }
        String status = switch (run.getRunStatus()) {
            case CANCELLED -> "SUPERSEDED";
            default -> run.getRunStatus().name();
        };
        ErrorCode error = errorOf(run);
        return new RunSummaryResponse(run.getId(), run.getInquiry().getId(), run.getInputVersion(),
                UUID.fromString(answerMessageId.asString()), status,
                error == null ? null : ErrorResponse.of(error, requestIdOf(run, input).toString(),
                        run.getInputVersion() == run.getInquiry().getVersion()));
    }

    /** 수동 재분석(RunRetryCommand)을 받을 수 있는 실패인지. 공개 오류의 retryable과 같은 기준이다. */
    public boolean isRetryable(AgentRun run) {
        ErrorCode error = errorOf(run);
        return error != null && error.retryable();
    }

    private static @Nullable ErrorCode errorOf(AgentRun run) {
        return switch (run.getRunStatus()) {
            case FAILED -> failureOf(run.getErrorCode());
            case TIMED_OUT -> ErrorCode.DEADLINE_EXCEEDED;
            default -> null;
        };
    }

    // AiError.code → 공개 코드 (WIRE_CONTRACT 12.8 끝). 이미 공개 코드로 저장한 값은 그대로, 알 수 없는 값은 확인 불가로 본다.
    private static ErrorCode failureOf(@Nullable String code) {
        return switch (code == null ? "" : code) {
            case "INVALID_OUTPUT", "AI_INVALID_OUTPUT" -> ErrorCode.AI_INVALID_OUTPUT;
            case "MODEL_UNAVAILABLE" -> ErrorCode.MODEL_UNAVAILABLE;
            case "SEARCH_UNAVAILABLE" -> ErrorCode.SEARCH_UNAVAILABLE;
            case "TOOL_UNAVAILABLE", "BUDGET_EXCEEDED", "SERVICE_UNAVAILABLE" -> ErrorCode.SERVICE_UNAVAILABLE;
            case "DEADLINE_EXCEEDED" -> ErrorCode.DEADLINE_EXCEEDED;
            case "AUTH_CONTEXT_INVALID", "AUTH_CONTEXT_CHANGED" -> ErrorCode.AUTH_CONTEXT_CHANGED;
            default -> ErrorCode.AI_RUN_STATE_UNKNOWN;
        };
    }

    // 시작 메시지는 USER 메시지라 requestId가 항상 있다. 정정한 메시지면 실행 입력에 고정된 그 메시지의 차수로 정정 요청을 찾는다
    // (메시지는 그 뒤에 또 정정됐을 수 있어 현재 차수를 쓰지 않는다).
    private UUID requestIdOf(AgentRun run, JsonNode input) {
        Optional<UUID> retry = idempotencyService.findRequestIdOf(new ResourceRef(ResourceType.RUN, run.getId()));
        if (retry.isPresent()) {
            return retry.get();
        }
        MessagePayload payload = jsonMapper.readValue(run.getTriggerMessage().getPayload(), MessagePayload.class);
        // 메시지 명령이 접수한 실행은 시작 메시지에 접수 명령을 기록한다. 기록 전에 만든 실행만 아래 차수로 찾는다
        UUID recorded = payload.runRequestId(run.getId());
        if (recorded != null) {
            return recorded;
        }
        UUID revisionRequestId = payload.revisionRequestId(triggerRevision(run, input));
        if (revisionRequestId != null) {
            return revisionRequestId;
        }
        UUID requestId = payload.requestId();
        if (requestId == null) {
            throw new IllegalStateException("시작 메시지에 requestId가 없다: " + run.getId());
        }
        return requestId;
    }

    private static int triggerRevision(AgentRun run, JsonNode input) {
        String triggerId = run.getTriggerMessage().getId().toString();
        for (JsonNode message : input.path("messages")) {
            if (triggerId.equals(message.path("id").asString(""))) {
                return message.path("revision").asInt(1);
            }
        }
        return 1;
    }
}
