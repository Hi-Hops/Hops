package com.hihops.hops.inquiry.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.hihops.hops.inquiry.domain.Inquiry;
import com.hihops.hops.inquiry.domain.InquiryStatus;

/**
 * 문의 상세 (specs/contracts/DATA_TYPES.md 신규 절 InquiryResult).
 *
 * <p>inquiryVersion은 DB 문의 버전(1부터)을 그대로 쓴다. resultRevision은 AI 결과를 적용할 때마다 늘어나는 별도 값이다.
 *
 * <p>problems(Problem[])와 currentRun(RunSummary)은 아직 형식을 만들지 않았다. 문제와 실행이 없는 문의만 이 응답으로 만들 수 있고,
 * 서비스가 그 조건을 확인한다. 문제·실행을 만드는 기능에서 각 형식으로 바꾼다 (#26).
 */
public record InquiryResultResponse(UUID id, String inquiryNumber, int inquiryVersion, InquiryStatus status,
        List<Object> problems, @Nullable Object currentRun, List<UUID> pendingTargetMessageIds, int resultRevision,
        Instant createdAt, Instant updatedAt) {

    /** 문제·실행이 아직 없는 문의. problems=[]·currentRun=null로 만든다. */
    public static InquiryResultResponse withoutProblems(Inquiry inquiry, List<UUID> pendingTargetMessageIds) {
        return new InquiryResultResponse(inquiry.getId(), inquiry.getInquiryNumber(), inquiry.getVersion(),
                inquiry.getInquiryStatus(), List.of(), null, List.copyOf(pendingTargetMessageIds),
                inquiry.getResultRevision(), inquiry.getCreatedAt(), inquiry.getUpdatedAt());
    }
}
