package com.hihops.hops.inquiry.dto;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.hihops.hops.inquiry.domain.Inquiry;
import com.hihops.hops.inquiry.domain.InquiryStatus;
import com.hihops.hops.inquiry.repository.InquiryProblemCounts;

/**
 * 내 문의 목록의 한 줄 (specs/contracts/DATA_TYPES.md 신규 절 InquirySummary). 담당자 내부 근거는 넣지 않는다.
 *
 * <p>status는 DB 값과 외부 값이 같아 그대로 보낸다 (docs/DATA.md 11.2 inquiry.inquiry_status).
 */
public record InquirySummaryResponse(UUID id, String inquiryNumber, String title, InquiryStatus status,
        int problemCount, int ticketCount, int pendingConfirmationCount, Instant createdAt, Instant updatedAt) {

    /** @param counts 문제 집계. 문제가 없는 문의는 집계 결과가 없어 null이고, 이때는 모두 0이다. */
    public static InquirySummaryResponse of(Inquiry inquiry, @Nullable InquiryProblemCounts counts) {
        return new InquirySummaryResponse(inquiry.getId(), inquiry.getInquiryNumber(), inquiry.getTitle(),
                inquiry.getInquiryStatus(),
                counts == null ? 0 : Math.toIntExact(counts.problemCount()),
                counts == null ? 0 : Math.toIntExact(counts.ticketCount()),
                counts == null ? 0 : Math.toIntExact(counts.pendingConfirmationCount()),
                inquiry.getCreatedAt(), inquiry.getUpdatedAt());
    }
}
