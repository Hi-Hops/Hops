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
import org.springframework.web.bind.annotation.RestController;

import com.hihops.hops.global.idempotency.CommandReceipt;
import com.hihops.hops.global.idempotency.IdempotentRequest;
import com.hihops.hops.inquiry.dto.ProblemResponse.CreationResultResponse;
import com.hihops.hops.inquiry.dto.WorkItemRetryCommand;
import com.hihops.hops.inquiry.service.WorkItemCommandService;
import com.hihops.hops.inquiry.service.WorkItemQueryService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 티켓 생성 작업 조회·재시도 (WIRE_CONTRACT 12.2). 실제 주소는 /api/v2/work-items (ApiPathConfig). */
@RestController
@RequestMapping("/work-items")
@RequiredArgsConstructor
public class WorkItemController {
    private final WorkItemQueryService workItems;
    private final WorkItemCommandService commands;

    @GetMapping("/{workItemId}")
    public CreationResultResponse workItem(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workItemId) {
        return workItems.find(UUID.fromString(jwt.getSubject()), workItemId);
    }

    @PostMapping("/{workItemId}/retries")
    public ResponseEntity<CommandReceipt> retry(@AuthenticationPrincipal Jwt jwt, HttpServletRequest http,
            @PathVariable UUID workItemId,
            @RequestHeader(value = IdempotentRequest.HEADER, required = false) @Nullable String idempotencyKey,
            @Valid @RequestBody WorkItemRetryCommand command) {
        IdempotentRequest request = IdempotentRequest.of(jwt, http, idempotencyKey, command.requestId(), command);
        return commands.retry(request, workItemId, command).toResponseEntity();
    }
}
