package com.hihops.hops.inquiry.dto;

import java.util.List;
import java.util.UUID;

import com.hihops.hops.global.validation.UserContent;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * POST /inquiries/{inquiryId}/messages 본문 (DATA_TYPES 신규 절 MessageSendCommand).
 *
 * <pre>
 * { "requestId": "UUID", "expectedInquiryVersion": 1, "content": "보고서 작성일은 10월 6일이에요", "problemIds": [] }
 * </pre>
 *
 * @param requestId              Idempotency-Key와 같은 값. 이 메시지의 requestId로 보존한다
 * @param expectedInquiryVersion 화면이 본 문의 버전. 다르면 409 INQUIRY_VERSION_CONFLICT
 * @param content                메시지 입력. 자르거나 trim하지 않으며 탐지된 일부 개인정보는 저장 전에 가린다
 * @param problemIds             사용자가 고른 대상 문제. []이면 분석으로 대상을 정한다
 */
public record MessageSendCommand(@NotNull UUID requestId,
        @NotNull @Min(1) @Max(Integer.MAX_VALUE) Integer expectedInquiryVersion, @NotNull @UserContent String content,
        @NotNull List<@NotNull UUID> problemIds) {
}
