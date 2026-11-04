package com.hihops.hops.inquiry.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.hihops.hops.global.exception.ErrorResponse;
import com.hihops.hops.ticket.dto.ResolutionRequestResponse;
import com.hihops.hops.ticket.dto.StandardRequestResponse.KnowledgeEvidenceResponse;
import com.hihops.hops.ticket.dto.TicketSummaryResponse;

/**
 * 문의자 화면의 문제 한 건 (DATA_TYPES 신규 절 Problem). InquiryResult.problems에 담는다.
 *
 * <p>담당자 내부 표준 요청서(확인 사실·추정·출처)는 담지 않는다. 시스템 확인 대기(WAITING_SYSTEM)면 reportedSystemId는 null이다.
 *
 * @param state                   ProblemState (DATA 11.2 변환, {@code ProblemStateView})
 * @param questionCount           질문 묶음 횟수 0~5
 * @param guide                   저장된 직원용 안내. 없으면 null
 * @param creationResult          티켓 생성 작업 결과. 작업이 없으면 null
 * @param ticket                  생성된 티켓. 없으면 null
 * @param activeResolutionRequest 현재 해결 확인 요청(응답한 요청 포함 최신). 없으면 null
 */
public record ProblemResponse(UUID id, UUID inquiryId, String summary, String intent, String processingType,
        @Nullable UUID reportedSystemId, @Nullable UUID reportedFeatureId, String state, int questionCount,
        @Nullable GuideAnswerResponse guide, @Nullable CreationResultResponse creationResult,
        @Nullable TicketSummaryResponse ticket, @Nullable ResolutionRequestResponse activeResolutionRequest) {

    /**
     * 저장된 안내 (DATA_TYPES GuideAnswer).
     *
     * @param messageId 안내를 담은 AI 메시지
     * @param evidence  지금 문의자에게 공개 가능한 직원용(EMPLOYEE) 근거만
     */
    public record GuideAnswerResponse(UUID messageId, List<KnowledgeEvidenceResponse> evidence) {
    }

    /**
     * 티켓 생성 작업 결과 (DATA_TYPES TicketCreationResult). workItemId = problemId이고 ticketId는 STORED에만 있다.
     * UNKNOWN(저장 여부 미확인)과 NOT_CREATED(미생성 확인)를 구분한다.
     */
    public record CreationResultResponse(UUID workItemId, UUID problemId, int inquiryVersion, String outcome,
            @Nullable UUID ticketId, @Nullable ErrorResponse error, Instant updatedAt) {
    }
}
