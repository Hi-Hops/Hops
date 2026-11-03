package com.hihops.hops.agent.service;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.agent.dto.RunSummaryResponse;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.global.exception.ErrorResponse;
import com.hihops.hops.inquiry.domain.MessagePayload;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 상담 실행을 공개 RunSummary로 바꾼다 (DATA_TYPES 신규 절 RunSummary, WIRE_CONTRACT 12.8 끝).
 *
 * <p>상태: DB CANCELLED는 SUPERSEDED로 보낸다. 오류는 FAILED·TIMED_OUT에만 붙이고, 내부 오류 코드는 공개 코드로 바꾼다.
 * 원래 오류 문구·AI 응답 본문은 내보내지 않는다. 오류의 requestId는 이 실행을 접수한 공개 명령 ID(시작 메시지의 requestId)다.
 *
 * <p>트랜잭션 안에서 부른다(시작 메시지를 지연 로딩한다).
 */
@Component
@RequiredArgsConstructor
public class RunSummaryReader {

    private final JsonMapper jsonMapper;

    /**
     * @throws IllegalStateException 상담 실행이 아니거나 입력에 answerMessageId가 없을 때. 저장 규칙이 깨진 것이라 빈 값으로 숨기지 않는다
     */
    public RunSummaryResponse read(AgentRun run) {
        if (run.getTriggerMessage() == null) {
            throw new IllegalStateException("상담 실행이 아니다: " + run.getId());
        }
        JsonNode answerMessageId = jsonMapper.readTree(run.getInputSnapshot()).path("answerMessageId");
        if (!answerMessageId.isString()) {
            throw new IllegalStateException("실행 입력에 answerMessageId가 없다: " + run.getId());
        }
        String status = switch (run.getRunStatus()) {
            case CANCELLED -> "SUPERSEDED";
            default -> run.getRunStatus().name();
        };
        ErrorCode error = switch (run.getRunStatus()) {
            case FAILED -> failureOf(run.getErrorCode());
            case TIMED_OUT -> ErrorCode.DEADLINE_EXCEEDED;
            default -> null;
        };
        return new RunSummaryResponse(run.getId(), run.getInquiry().getId(), run.getInputVersion(),
                UUID.fromString(answerMessageId.asString()), status,
                error == null ? null : ErrorResponse.of(error, requestIdOf(run).toString()));
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

    // 시작 메시지는 USER 메시지라 requestId가 항상 있다
    private UUID requestIdOf(AgentRun run) {
        UUID requestId = jsonMapper.readValue(run.getTriggerMessage().getPayload(), MessagePayload.class).requestId();
        if (requestId == null) {
            throw new IllegalStateException("시작 메시지에 requestId가 없다: " + run.getId());
        }
        return requestId;
    }
}
