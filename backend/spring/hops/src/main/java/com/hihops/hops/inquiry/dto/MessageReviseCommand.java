package com.hihops.hops.inquiry.dto;

import java.util.UUID;

import com.hihops.hops.global.validation.UserContent;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * POST /inquiries/{inquiryId}/messages/{messageId}/revisions 본문 (DATA_TYPES 신규 절 MessageReviseCommand).
 *
 * <pre>
 * { "requestId": "UUID", "expectedInquiryVersion": 2, "expectedMessageRevision": 1, "content": "작성일은 10월 7일이에요" }
 * </pre>
 *
 * @param requestId               Idempotency-Key와 같은 값. 메시지의 requestId(처음 보낸 명령)는 바뀌지 않는다
 * @param expectedInquiryVersion  화면이 본 문의 버전. 다르면 409 INQUIRY_VERSION_CONFLICT
 * @param expectedMessageRevision 화면이 본 메시지 차수. 다르면 409 MESSAGE_REVISION_CONFLICT
 * @param content                 정정 입력. 자르거나 trim하지 않으며 탐지된 일부 개인정보는 저장 전에 가린다
 */
public record MessageReviseCommand(@NotNull UUID requestId,
        @NotNull @Min(1) @Max(Integer.MAX_VALUE) Integer expectedInquiryVersion,
        @NotNull @Min(1) @Max(Integer.MAX_VALUE) Integer expectedMessageRevision, @NotNull @UserContent String content) {
}
