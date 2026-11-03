package com.hihops.hops.ticket.dto;

import java.time.Instant;
import java.util.UUID;

import com.hihops.hops.itsystem.dto.SupportedSystemResponse;
import com.hihops.hops.ticket.domain.Ticket;
import com.hihops.hops.user.dto.UserSummaryResponse;

public record TicketSummaryResponse(UUID id, UUID inquiryId, UUID problemId, UUID workItemId,
        String ticketNumber, String title, String processingType, SupportedSystemResponse reportedSystem,
        SupportedSystemResponse responsibleSystem, UserSummaryResponse assignee, TicketState state,
        int ticketRevision, Instant createdAt, Instant updatedAt) {
    public static TicketSummaryResponse of(Ticket ticket, SupportedSystemResponse reported,
            SupportedSystemResponse responsible) {
        UUID problemId = ticket.getInquiryProblem().getId();
        return new TicketSummaryResponse(ticket.getId(), ticket.getInquiry().getId(), problemId, problemId,
                ticket.getTicketNumber(), ticket.getTitle(), ticket.getWorkType().name(), reported, responsible,
                UserSummaryResponse.from(ticket.getAssignee()), TicketState.from(ticket.getTicketStatus()),
                Math.addExact(ticket.getVersion(), 1), ticket.getCreatedAt(), ticket.getUpdatedAt());
    }
}
