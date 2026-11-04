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

import com.hihops.hops.global.idempotency.CommandReceipt;
import com.hihops.hops.global.idempotency.IdempotentRequest;
import com.hihops.hops.ticket.dto.ResolutionRequestResponse;
import com.hihops.hops.ticket.dto.ResolutionResponseCommand;
import com.hihops.hops.ticket.service.ResolutionRequestService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/resolution-requests/{resolutionRequestId}")
@RequiredArgsConstructor
public class ResolutionRequestController {
    private final ResolutionRequestService requests;

    @GetMapping
    public ResolutionRequestResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID resolutionRequestId) {
        return requests.get(jwt, resolutionRequestId);
    }

    @PostMapping("/responses")
    public ResponseEntity<CommandReceipt> respond(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID resolutionRequestId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody ResolutionResponseCommand body, HttpServletRequest http) {
        return requests.respond(jwt, IdempotentRequest.of(jwt, http, key, body.requestId(), body), resolutionRequestId, body)
                .toResponseEntity();
    }
}
