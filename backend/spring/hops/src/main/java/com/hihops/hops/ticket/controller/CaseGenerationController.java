package com.hihops.hops.ticket.controller;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hihops.hops.ticket.dto.CaseGenerationResponse;
import com.hihops.hops.ticket.service.TicketService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/case-generations")
@RequiredArgsConstructor
public class CaseGenerationController {
    private final TicketService tickets;

    @GetMapping("/{generationId}")
    public CaseGenerationResponse detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID generationId) {
        return tickets.caseGeneration(jwt, generationId);
    }
}
