package com.hihops.hops.ticket.controller;

import java.util.UUID;

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

import org.jspecify.annotations.Nullable;

import com.hihops.hops.global.idempotency.CommandReceipt;
import com.hihops.hops.global.idempotency.IdempotentRequest;
import com.hihops.hops.ticket.dto.CaseGenerationResponse;
import com.hihops.hops.ticket.dto.CaseGenerationRetryCommand;
import com.hihops.hops.ticket.service.CaseGenerationRetryService;
import com.hihops.hops.ticket.service.TicketService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/case-generations")
@RequiredArgsConstructor
public class CaseGenerationController {
    private final TicketService tickets;
    private final CaseGenerationRetryService retries;

    @GetMapping("/{generationId}")
    public CaseGenerationResponse detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID generationId) {
        return tickets.caseGeneration(jwt, generationId);
    }

    @PostMapping("/{generationId}/retries")
    public ResponseEntity<CommandReceipt> retry(@AuthenticationPrincipal Jwt jwt, HttpServletRequest http,
            @PathVariable UUID generationId,
            @RequestHeader(value = IdempotentRequest.HEADER, required = false) @Nullable String idempotencyKey,
            @Valid @RequestBody CaseGenerationRetryCommand command) {
        IdempotentRequest request = IdempotentRequest.of(jwt, http, idempotencyKey, command.requestId(), command);
        return retries.retry(jwt, request, generationId).toResponseEntity();
    }
}
