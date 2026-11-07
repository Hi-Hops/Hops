package com.hihops.hops.inquiry.dto;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.hihops.hops.global.validation.UserContent;

import jakarta.validation.constraints.NotNull;

/**
 * POST /inquiries 본문 (DATA_TYPES 신규 절 InquiryCreateCommand).
 *
 * <pre>
 * { "requestId": "UUID", "content": "결재 문서가 안 열려요", "reportedSystemId": null }
 * </pre>
 *
 * @param requestId        Idempotency-Key와 같은 값. 첫 USER 메시지의 requestId로 보존한다
 * @param content          첫 메시지 입력. 자르거나 trim하지 않으며 탐지된 일부 개인정보는 저장 전에 가린다
 * @param reportedSystemId 사용자가 고른 시스템. 고르지 않아도 문의를 시작할 수 있다
 */
public record InquiryCreateCommand(@NotNull UUID requestId, @NotNull @UserContent String content,
        @Nullable UUID reportedSystemId) {
}
