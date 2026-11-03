package com.hihops.hops.ticket.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hihops.hops.global.idempotency.CommandReceipt;
import com.hihops.hops.global.idempotency.IdempotencyService;
import com.hihops.hops.global.idempotency.IdempotentRequest;
import com.hihops.hops.ticket.dto.AssignmentCorrectionCommand;
import com.hihops.hops.ticket.dto.ProcessingTypeCorrectionCommand;
import com.hihops.hops.ticket.service.TicketCommandAccess;
import com.hihops.hops.ticket.service.TicketCorrectionService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/tickets/{ticketId}")
@RequiredArgsConstructor
public class TicketCorrectionController {
    private final IdempotencyService idempotency;
    private final TicketCommandAccess access;
    private final TicketCorrectionService corrections;

    @PostMapping("/processing-type-corrections")
    public ResponseEntity<CommandReceipt> processing(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID ticketId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody ProcessingTypeCorrectionCommand body, HttpServletRequest http) {
        access.check(jwt, ticketId, false);
        return idempotency.execute(IdempotentRequest.of(jwt, http, key, body.requestId(), body),
                operation -> corrections.processingType(jwt, ticketId, body)).toResponseEntity();
    }

    @PostMapping("/assignment-corrections")
    public ResponseEntity<CommandReceipt> assignment(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID ticketId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AssignmentCorrectionCommand body, HttpServletRequest http) {
        access.check(jwt, ticketId, true);
        return idempotency.execute(IdempotentRequest.of(jwt, http, key, body.requestId(), body),
                operation -> corrections.assignment(jwt, ticketId, body)).toResponseEntity();
    }
}
