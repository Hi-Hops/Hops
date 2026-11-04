package com.hihops.hops.agent.dto;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.hihops.hops.inquiry.domain.SenderType;
import com.hihops.hops.itsystem.dto.SupportedSystemResponse;
import com.hihops.hops.ticket.dto.StandardRequestResponse.EvidenceStatementResponse;
import com.hihops.hops.ticket.dto.StandardRequestResponse.KnowledgeEvidenceResponse;
import com.hihops.hops.ticket.dto.StandardRequestResponse.RequestDetailsResponse;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Spring → AI 실행 입력 (contracts/agents/README.md AiRunRequest). agent_run.input_snapshot에 이 JSON을 그대로 고정한다.
 *
 * <p>AI 서버는 계약에 없는 필드를 거부한다(extra=forbid). 그래서 필드를 더하거나 빼지 않고, null 값도 키를 생략하지 않는다.
 * 시각은 UTC RFC 3339 Z 문자열이며 소수초는 최대 6자리다 ({@link #time}).
 *
 * <p>existingWorkItems·resolutionContext는 아직 투영하지 않아 빈 목록·null을 보낸다.
 */
public record AiRunRequest(String contractVersion, UUID runId, String kind, UUID inquiryId, int inquiryVersion,
        UUID answerMessageId, List<UUID> problemIds, List<InputMessage> messages, List<InputProblem> problems,
        List<SupportedSystemResponse> systems, AccessContext accessContext, List<Object> existingWorkItems,
        @Nullable Object workRecord, String expiresAt, ExecutionLimits limits) {

    /** AI에 보여 줄 저장된 메시지. revision·content는 현재 저장값이다. */
    public record InputMessage(UUID id, int revision, int sequenceNo, SenderType sender, String content,
            List<UUID> problemIds, String createdAt) {
    }

    /**
     * 기존 문제. 상태·질문 수는 inquiry_problem, 의도·처리 유형·시스템·요청서는 최신 적용 실행의 standard_request에서 읽는다.
     * state·intent·processingType은 계약 값 이름 그대로다.
     */
    public record InputProblem(UUID id, String summary, String intent, String processingType,
            @Nullable UUID reportedSystemId, @Nullable UUID reportedFeatureId, String state, int questionCount,
            @Nullable StandardRequestBody standardRequest, @Nullable Object resolutionContext) {
    }

    /** 문제별 표준 요청서 본문. 공개 범위는 문의자(직원) 기준으로 거른 값이다. */
    public record StandardRequestBody(String summary, List<EvidenceStatementResponse> confirmedFacts,
            List<EvidenceStatementResponse> assumptions, List<String> unknowns,
            @Nullable UUID responsibleSystemCandidateId, List<UUID> responsibilityEvidenceIds,
            RequestDetailsResponse details, List<KnowledgeEvidenceResponse> knowledge) {
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
