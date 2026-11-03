package com.hihops.hops.ticket.dto;

import java.time.Instant;
import java.util.UUID;

import com.hihops.hops.ticket.domain.TicketWorkLog;
import com.hihops.hops.user.dto.UserSummaryResponse;

public record WorkRecordResponse(UUID id, UUID ticketId, String kind, String action, String verification,
        String confirmedCause, String appliesToSystemVersion, UserSummaryResponse author, Instant createdAt) {
    public static WorkRecordResponse from(TicketWorkLog value) {
        String kind = switch (value.getLogType()) {
            case PROGRESS -> "PROGRESS";
            case FAILED_ATTEMPT -> "FAILED_ATTEMPT";
            case RESOLVED -> "VERIFIED_RESOLUTION";
            case IMPLEMENTED -> "IMPLEMENTED_VERIFIED";
        };
        return new WorkRecordResponse(value.getId(), value.getTicket().getId(), kind, value.getActionDetail(),
                value.getVerificationResult(), value.getConfirmedCause(), value.getAppliedVersion(),
                UserSummaryResponse.from(value.getAuthor()), value.getCreatedAt());
    }
}
