package com.hihops.hops.ticket.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * POST /case-generations/{generationId}/retries 본문 (WIRE_CONTRACT 12.2).
 *
 * <pre>
 * { "requestId": "UUID" }
 * </pre>
 *
 * @param requestId Idempotency-Key와 같은 값. 경로의 원본 generationId와 함께 같은 요청인지 판단한다
 */
public record CaseGenerationRetryCommand(@NotNull UUID requestId) {
}
