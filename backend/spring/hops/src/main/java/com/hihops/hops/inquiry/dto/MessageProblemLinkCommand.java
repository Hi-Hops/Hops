package com.hihops.hops.inquiry.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
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
 * @param problemIds             이 메시지가 보완하는 이 문의의 기존 문제. 비어 있으면 안 된다
 */
public record MessageProblemLinkCommand(@NotNull UUID requestId,
        @NotNull @Min(1) @Max(Integer.MAX_VALUE) Integer expectedInquiryVersion,
        @NotEmpty List<@NotNull UUID> problemIds) {
}
