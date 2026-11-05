package com.hihops.hops.inquiry.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.hihops.hops.agent.dto.RunSummaryResponse;
import com.hihops.hops.inquiry.domain.Inquiry;
import com.hihops.hops.inquiry.domain.InquiryStatus;

/**
 * 문의 상세 (specs/contracts/DATA_TYPES.md 신규 절 InquiryResult).
 *
 * <p>inquiryVersion은 DB 문의 버전(1부터)을 그대로 쓴다. resultRevision은 티켓 생성 결과나 해결 응답이 바뀔 때 늘어나는 별도 값이다.
 *
 * @param problems   문제별 결과 (문제 번호 순). 담당자 내부 표준 요청서는 담지 않는다
 * @param currentRun 마지막으로 접수한 상담 실행. 실행이 끝나도 다음 실행 전까지 그대로 가리킨다
 */
public record InquiryResultResponse(UUID id, String inquiryNumber, int inquiryVersion, InquiryStatus status,
        List<ProblemResponse> problems, @Nullable RunSummaryResponse currentRun, List<UUID> pendingTargetMessageIds, int resultRevision,
        Instant createdAt, Instant updatedAt) {

    public static InquiryResultResponse of(Inquiry inquiry, List<ProblemResponse> problems,
            @Nullable RunSummaryResponse currentRun, List<UUID> pendingTargetMessageIds) {
        return new InquiryResultResponse(inquiry.getId(), inquiry.getInquiryNumber(), inquiry.getVersion(),
                inquiry.getInquiryStatus(), List.copyOf(problems), currentRun, List.copyOf(pendingTargetMessageIds),
                inquiry.getResultRevision(), inquiry.getCreatedAt(), inquiry.getUpdatedAt());
    }
}
