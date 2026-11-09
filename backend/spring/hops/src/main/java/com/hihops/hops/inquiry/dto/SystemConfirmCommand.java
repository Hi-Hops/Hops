package com.hihops.hops.inquiry.dto;

import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * POST /inquiries/{inquiryId}/problems/{problemId}/system-confirmations 본문 (DATA_TYPES 신규 절 SystemConfirmCommand).
 *
 * <pre>
 * { "requestId": "UUID", "expectedInquiryVersion": 2, "systemId": "UUID" }
 * </pre>
 *
 * @param requestId              Idempotency-Key와 같은 값. 경로의 문의·문제와 함께 같은 요청인지 판단한다
 * @param expectedInquiryVersion 화면이 본 문의 버전. 다르면 409 INQUIRY_VERSION_CONFLICT
 * @param systemId               문의자가 고른 지원 시스템. 지원 시스템이 아니면 400 INVALID_INPUT
 */
public record SystemConfirmCommand(@NotNull UUID requestId,
        @NotNull @Min(1) @Max(Integer.MAX_VALUE) Integer expectedInquiryVersion, @NotNull UUID systemId) {
}
