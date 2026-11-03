package com.hihops.hops.ticket.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.hihops.hops.ticket.domain.TicketAssignment;
import com.hihops.hops.user.dto.UserSummaryResponse;

public record AssignmentHistoryResponse(UUID id, UUID previousSystemId, UUID previousAssigneeId, UUID systemId,
        UserSummaryResponse assignee, String reason, List<UUID> evidenceMessageIds, String evidenceNote,
        UUID actorId, Instant createdAt) {
    public static AssignmentHistoryResponse of(TicketAssignment value, TicketAssignment previous,
            List<UUID> messageIds, String note) {
        return new AssignmentHistoryResponse(value.getId(), previous == null ? null : previous.getItSystem().getId(),
                previous == null ? null : previous.getAssignee().getId(), value.getItSystem().getId(),
                UserSummaryResponse.from(value.getAssignee()), value.getReason(), messageIds, note,
                value.getAssignedBy() == null ? null : value.getAssignedBy().getId(), value.getAssignedAt());
    }
}
