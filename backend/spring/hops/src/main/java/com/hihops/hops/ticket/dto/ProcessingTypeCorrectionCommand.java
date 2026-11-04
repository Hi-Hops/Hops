package com.hihops.hops.ticket.dto;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ProcessingTypeCorrectionCommand(@NotNull UUID requestId, @NotNull @Min(1) Integer expectedTicketRevision,
        @NotNull Type processingType, String reason, @NotNull List<@NotNull UUID> evidenceMessageIds,
        @JsonProperty(required = true) String evidenceNote) {
    public enum Type { INVESTIGATION, INCIDENT }
    public ProcessingTypeCorrectionCommand {
        CommandInput.text(reason, 1000, false);
        CommandInput.text(evidenceNote, 1000, true);
    }
}
