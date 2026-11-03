package com.hihops.hops.inquiry.repository;

import java.util.UUID;

/**
 * 문의 한 건의 문제 집계 (InquirySummary의 problemCount·ticketCount·pendingConfirmationCount).
 * {@link InquiryProblemRepository#countByInquiryIds}가 JPQL로 바로 만든다. JPQL의 COUNT·SUM 결과가 Long이라 Long으로 받는다.
 */
public record InquiryProblemCounts(UUID inquiryId, Long problemCount, Long ticketCount, Long pendingConfirmationCount) {
}
