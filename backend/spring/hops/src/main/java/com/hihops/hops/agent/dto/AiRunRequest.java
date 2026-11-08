package com.hihops.hops.agent.dto;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;

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
 * <p>AI 서버는 계약에 없는 필드를 거부한다(extra=forbid). 선택 이미지 참조가 없는 기존 입력은 필드를 생략한다.
 * 시각은 UTC RFC 3339 Z 문자열이며 소수초는 최대 6자리다 ({@link #time}).
 *
 * <p>resolutionContext는 아직 투영하지 않아 null을 보낸다.
 */
public record AiRunRequest(String contractVersion, UUID runId, String kind, UUID inquiryId, int inquiryVersion,
        UUID answerMessageId, List<UUID> problemIds, List<InputMessage> messages, List<InputProblem> problems,
        List<SupportedSystemResponse> systems, AccessContext accessContext, List<ExistingWorkItem> existingWorkItems,
        @Nullable Object workRecord, String expiresAt, ExecutionLimits limits) {

    /** AI에 보여 줄 저장된 메시지. revision·content는 현재 저장값이다. */
    public record InputMessage(UUID id, int revision, int sequenceNo, SenderType sender, String content,
            List<UUID> problemIds, String createdAt,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) @Nullable List<ImageRef> imageAttachments) {
        public InputMessage(UUID id, int revision, int sequenceNo, SenderType sender, String content,
                List<UUID> problemIds, String createdAt) {
            this(id, revision, sequenceNo, sender, content, problemIds, createdAt, null);
        }
    }

    /** 이미지 바이트나 저장소 위치가 없는 실행 입력 참조. */
    public record ImageRef(UUID attachmentId, String digest) { }

    /**
     * 기존 문제. 상태·질문 수는 inquiry_problem, 의도·처리 유형·시스템·요청서는 최신 적용 실행의 standard_request에서 읽는다.
     * state·intent·processingType은 계약 값 이름 그대로다.
     *
     * <p>askedFields는 이 문제에 이미 물은 항목이다(AI 메시지 payload.questionBundles[].requestedFields의 합집합, 물은 순서).
     * 답을 못 얻은 항목도 AI가 다시 묻지 않게 하려는 질문 이력이다. 물은 적 없으면 빈 목록이다.
     */
    public record InputProblem(UUID id, String summary, String intent, String processingType,
            @Nullable UUID reportedSystemId, @Nullable UUID reportedFeatureId, String state, int questionCount,
            List<String> askedFields, @Nullable StandardRequestBody standardRequest,
            @Nullable Object resolutionContext) {
    }

    /** 문제별 표준 요청서 본문. 공개 범위는 문의자(직원) 기준으로 거른 값이다. */
    public record StandardRequestBody(String summary, List<EvidenceStatementResponse> confirmedFacts,
            List<EvidenceStatementResponse> assumptions, List<String> unknowns,
            @Nullable UUID responsibleSystemCandidateId, List<UUID> responsibilityEvidenceIds,
            RequestDetailsResponse details, List<KnowledgeEvidenceResponse> knowledge) {
    }

    /**
     * 문제의 티켓 생성 작업 결과 (workItemId = problemId). 재분석해도 유지해야 할 서버 결과라 AI가 같은 문제의 티켓을 다시 만들지 않는다.
     * ticketId는 STORED에만 있다.
     */
    public record ExistingWorkItem(UUID workItemId, UUID problemId, int inquiryVersion, String outcome,
            @Nullable UUID ticketId) {
    }

    /**
     * Spring이 만든 최소 권한. 모델이 넓히지 못한다.
     *
     * <p>{@code allowedDocumentScopes}는 사용자에게 닿을 수 있는 자료 범위다. {@code retrievalScopes}는 **내부 판단 경계(옵션, 기본 꺼짐)**에서
     * 서버 설정이 켰을 때만 채우는 가산 필드로, 담당자 전용 자료를 내부 판단 입력으로만 읽을 수 있는 범위다(allowedDocumentScopes를 모두 포함).
     * 꺼져 있으면 null이고 JSON에도 나타나지 않는다. 읽은 담당자 전용 자료가 사용자 응답에 닿는 경로는 열지 않는다
     * (docs/ai/AGENT_ARCHITECTURE.md 9절, contracts/agents/KNOWLEDGE_TOOLS.md).
     */
    public record AccessContext(UUID actorId, String purpose, List<UUID> systemIds, List<String> allowedDocumentScopes,
            int policyRevision, @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable List<String> retrievalScopes) {
        public AccessContext(UUID actorId, String purpose, List<UUID> systemIds, List<String> allowedDocumentScopes,
                int policyRevision) {
            this(actorId, purpose, systemIds, allowedDocumentScopes, policyRevision, null);
        }
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
