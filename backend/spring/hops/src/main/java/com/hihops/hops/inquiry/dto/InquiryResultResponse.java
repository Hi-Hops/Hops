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
 * <p>inquiryVersion은 DB 문의 버전(1부터)을 그대로 쓴다. resultRevision은 AI 결과를 적용할 때마다 늘어나는 별도 값이다.
 *
 * <p>problems(Problem[])는 아직 형식을 만들지 않았다. 문제가 없는 문의만 이 응답으로 만들 수 있고, 서비스가 그 조건을 확인한다.
 * AI 결과로 문제를 저장하는 기능에서 Problem 형식으로 바꾼다 (#26).
 *
 * @param currentRun 마지막으로 접수한 상담 실행. 실행이 끝나도 다음 실행 전까지 그대로 가리킨다
 */
public record InquiryResultResponse(UUID id, String inquiryNumber, int inquiryVersion, InquiryStatus status,
        List<Object> problems, @Nullable RunSummaryResponse currentRun, List<UUID> pendingTargetMessageIds, int resultRevision,
        Instant createdAt, Instant updatedAt) {

    /** 문제가 아직 없는 문의. problems=[]로 만든다. */
    public static InquiryResultResponse withoutProblems(Inquiry inquiry, @Nullable RunSummaryResponse currentRun,
            List<UUID> pendingTargetMessageIds) {
        return new InquiryResultResponse(inquiry.getId(), inquiry.getInquiryNumber(), inquiry.getVersion(),
                inquiry.getInquiryStatus(), List.of(), currentRun, List.copyOf(pendingTargetMessageIds),
                inquiry.getResultRevision(), inquiry.getCreatedAt(), inquiry.getUpdatedAt());
    }
}
