package com.hihops.hops.agent.dto;

import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * POST /runs/{runId}/retries 본문 (DATA_TYPES 신규 절 RunRetryCommand).
 *
 * <pre>
 * { "requestId": "UUID", "expectedInquiryVersion": 1 }
 * </pre>
 *
 * @param requestId              Idempotency-Key와 같은 값. 경로의 원본 runId와 함께 같은 요청인지 판단한다
 * @param expectedInquiryVersion 화면이 본 문의 버전. 다르면 409 INQUIRY_VERSION_CONFLICT
 */
public record RunRetryCommand(@NotNull UUID requestId, @NotNull @Min(1) @Max(Integer.MAX_VALUE) Integer expectedInquiryVersion) {
}
