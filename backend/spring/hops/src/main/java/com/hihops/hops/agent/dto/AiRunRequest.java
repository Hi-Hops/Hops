package com.hihops.hops.agent.dto;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.hihops.hops.inquiry.domain.SenderType;
import com.hihops.hops.itsystem.dto.SupportedSystemResponse;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Spring → AI 실행 입력 (contracts/agents/README.md AiRunRequest). agent_run.input_snapshot에 이 JSON을 그대로 고정한다.
 *
 * <p>AI 서버는 계약에 없는 필드를 거부한다(extra=forbid). 그래서 필드를 더하거나 빼지 않고, null 값도 키를 생략하지 않는다.
 * 시각은 UTC RFC 3339 Z 문자열이며 소수초는 최대 6자리다 ({@link #time}).
 *
 * <p>problems·existingWorkItems는 아직 문제·업무를 투영하지 않아 빈 목록만 보낸다. 첫 분석은 원래 둘 다 비어 있다.
 */
public record AiRunRequest(String contractVersion, UUID runId, String kind, UUID inquiryId, int inquiryVersion,
        UUID answerMessageId, List<UUID> problemIds, List<InputMessage> messages, List<Object> problems,
        List<SupportedSystemResponse> systems, AccessContext accessContext, List<Object> existingWorkItems,
        @Nullable Object workRecord, String expiresAt, ExecutionLimits limits) {

    /** AI에 보여 줄 저장된 메시지. revision·content는 현재 저장값이다. */
    public record InputMessage(UUID id, int revision, int sequenceNo, SenderType sender, String content,
            List<UUID> problemIds, String createdAt) {
    }

    /** Spring이 만든 최소 권한. 모델이 넓히지 못한다. */
    public record AccessContext(UUID actorId, String purpose, List<UUID> systemIds, List<String> allowedDocumentScopes,
            int policyRevision) {
    }

    /** 실행 전체의 누적 한도. maxRetries만 0을 허용한다. */
    public record ExecutionLimits(@Positive int maxModelCalls, @Positive int maxInputTokens,
            @Positive int maxOutputTokens, @Positive int maxToolCalls, @PositiveOrZero int maxRetries) {
    }

    /** 계약 시각 형식. Instant.toString은 소수초를 9자리까지 쓰므로 마이크로초까지만 남긴다. */
    public static String time(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).toString();
    }
}
