package com.hihops.hops.agent.internal;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/internal/v2/runs/{runId}/ticket-creations")
@RequiredArgsConstructor
public class TicketCreationController {
    private final TicketCreationService service;

    @PostMapping
    public TicketCreationResult create(@PathVariable UUID runId, @Valid @RequestBody TicketCreationCommand command) {
        return service.create(runId, command);
    }

    @GetMapping("/{problemRef}")
    public TicketCreationResult get(@PathVariable UUID runId, @PathVariable String problemRef) {
        return service.get(runId, problemRef);
    }

    @PostMapping("/{problemRef}/retries")
    public TicketCreationResult retry(@PathVariable UUID runId, @PathVariable String problemRef,
            @Valid @RequestBody TicketCreationCommand.Retry command) {
        return service.retry(runId, problemRef, command);
    }
}
