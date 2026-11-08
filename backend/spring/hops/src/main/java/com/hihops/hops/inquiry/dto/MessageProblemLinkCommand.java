package com.hihops.hops.inquiry.dto;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * POST /inquiries/{inquiryId}/messages/{messageId}/problem-links 본문 (DATA_TYPES 신규 절 MessageProblemLinkCommand).
 *
 * <pre>
 * { "requestId": "UUID", "expectedInquiryVersion": 3, "problemIds": ["UUID"] }
 * </pre>
 *
 * @param requestId              Idempotency-Key와 같은 값
 * @param expectedInquiryVersion 화면이 본 문의 버전. 다르면 409 INQUIRY_VERSION_CONFLICT
 * @param problemIds             이 메시지가 보완하는 기존 문제. 새 문제로 분석할 때만 빈 목록이다
 * @param newProblem             true이면 원문을 이 상담의 새 문제로 분석한다
 */
public record MessageProblemLinkCommand(@NotNull UUID requestId,
        @NotNull @Min(1) @Max(Integer.MAX_VALUE) Integer expectedInquiryVersion,
        @NotNull List<@NotNull UUID> problemIds,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable Boolean newProblem) {
}
