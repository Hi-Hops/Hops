package com.hihops.hops.ticket.controller;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hihops.hops.ticket.dto.RelatedMessagePageResponse;
import com.hihops.hops.ticket.dto.RelatedMessageRequest;
import com.hihops.hops.ticket.dto.TicketDetailResponse;
import com.hihops.hops.ticket.dto.TicketListRequest;
import com.hihops.hops.ticket.dto.TicketPageResponse;
import com.hihops.hops.ticket.service.RelatedMessageService;
import com.hihops.hops.ticket.service.TicketService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/tickets")
@RequiredArgsConstructor
public class TicketController {
    private final TicketService tickets;
    private final RelatedMessageService related;

    @GetMapping
    public TicketPageResponse list(@AuthenticationPrincipal Jwt jwt, @Valid @ModelAttribute TicketListRequest request) {
        return tickets.list(jwt, request);
    }

    @GetMapping("/{ticketId}")
    public TicketDetailResponse detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID ticketId) {
        return tickets.detail(jwt, ticketId);
    }

    @GetMapping("/{ticketId}/related-messages")
    public RelatedMessagePageResponse related(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID ticketId,
            @Valid @ModelAttribute RelatedMessageRequest request) {
        return related.list(jwt, ticketId, request);
    }
}
