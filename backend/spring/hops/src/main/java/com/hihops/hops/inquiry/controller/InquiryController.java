package com.hihops.hops.inquiry.controller;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hihops.hops.global.dto.PageResponse;
import com.hihops.hops.global.idempotency.CommandReceipt;
import com.hihops.hops.global.idempotency.IdempotentRequest;
import com.hihops.hops.inquiry.dto.InquiryCreateCommand;
import com.hihops.hops.inquiry.dto.InquiryResultResponse;
import com.hihops.hops.inquiry.dto.InquirySummaryResponse;
import com.hihops.hops.inquiry.dto.MessagePageResponse;
import com.hihops.hops.inquiry.dto.MessageSendCommand;
import com.hihops.hops.inquiry.service.InquiryCommandService;
import com.hihops.hops.inquiry.service.InquiryQueryService;
import com.hihops.hops.inquiry.service.MessageCommandService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/**
 * 문의자 본인의 문의 생성·메시지 전송과 문의·대화 조회 API (WIRE_CONTRACT 12.2).
 *
 * <p>생성·전송은 Idempotency-Key 헤더와 본문 requestId가 같아야 한다. 응답은 CommandReceipt이며 프론트는 resource의 문의를 다시 GET한다.
 *
 * <p>페이지 값이 범위를 벗어나면 고쳐서 처리하지 않고 400이다 (12.2 "잘못된 필터·범위는 400이며 임의 보정하지 않는다").
 * 목록은 기본 20개, 대화는 기본 30개이고 둘 다 최대 100개다.
 */
@RestController
@RequestMapping("/inquiries") // 실제 주소는 /api/v2/inquiries (ApiPathConfig)
@RequiredArgsConstructor
public class InquiryController {

    private final InquiryQueryService inquiryQueryService;
    private final InquiryCommandService inquiryCommandService;
    private final MessageCommandService messageCommandService;

    @PostMapping
    public ResponseEntity<CommandReceipt> create(@AuthenticationPrincipal Jwt jwt, HttpServletRequest http,
            @RequestHeader(value = IdempotentRequest.HEADER, required = false) @Nullable String idempotencyKey,
            @Valid @RequestBody InquiryCreateCommand command) {
        IdempotentRequest request = IdempotentRequest.of(jwt, http, idempotencyKey, command.requestId(), command);
        return inquiryCommandService.create(request, command).toResponseEntity();
    }

    @GetMapping
    public PageResponse<InquirySummaryResponse> inquiries(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return inquiryQueryService.findMyInquiries(actorId(jwt), page, size);
    }

    @GetMapping("/{inquiryId}")
    public InquiryResultResponse inquiry(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID inquiryId) {
        return inquiryQueryService.findMyInquiry(actorId(jwt), inquiryId);
    }

    @PostMapping("/{inquiryId}/messages")
    public ResponseEntity<CommandReceipt> send(@AuthenticationPrincipal Jwt jwt, HttpServletRequest http,
            @PathVariable UUID inquiryId,
            @RequestHeader(value = IdempotentRequest.HEADER, required = false) @Nullable String idempotencyKey,
            @Valid @RequestBody MessageSendCommand command) {
        IdempotentRequest request = IdempotentRequest.of(jwt, http, idempotencyKey, command.requestId(), command);
        return messageCommandService.send(request, inquiryId, command).toResponseEntity();
    }

    @GetMapping("/{inquiryId}/messages")
    public MessagePageResponse messages(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID inquiryId,
            @RequestParam(defaultValue = "30") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Min(1) @Nullable Integer beforeSequenceNo) {
        return inquiryQueryService.findMyMessages(actorId(jwt), inquiryId, size, beforeSequenceNo);
    }

    private static UUID actorId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject()); // sub = 회원 UUID (AccessTokenIssuer)
    }
}
