package com.hihops.hops.ticket.dto;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record WorkRecordCommand(@NotNull UUID requestId, @NotNull @Min(1) Integer expectedTicketRevision,
        @NotNull Kind kind, String action, @JsonProperty(required = true) String verification,
        @JsonProperty(required = true) String confirmedCause,
        @JsonProperty(required = true) String appliesToSystemVersion) {
    public enum Kind { PROGRESS, FAILED_ATTEMPT, VERIFIED_RESOLUTION, IMPLEMENTED_VERIFIED }
    public WorkRecordCommand {
        CommandInput.text(action, Integer.MAX_VALUE, false);
        CommandInput.text(verification, Integer.MAX_VALUE, true);
        CommandInput.text(confirmedCause, 4000, true);
        CommandInput.text(appliesToSystemVersion, 100, true);
    }
    public boolean verified() { return kind == Kind.VERIFIED_RESOLUTION || kind == Kind.IMPLEMENTED_VERIFIED; }
}
