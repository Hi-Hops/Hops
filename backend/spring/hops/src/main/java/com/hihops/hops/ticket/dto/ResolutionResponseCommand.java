package com.hihops.hops.ticket.dto;

import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ResolutionResponseCommand(@NotNull UUID requestId, @NotNull @Min(1) Integer expectedRequestRevision,
        @NotNull Decision decision) {
    public enum Decision { RESOLVED, UNRESOLVED }
}
