package com.hihops.hops.ticket.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.hihops.hops.itsystem.dto.SupportedSystemResponse;
import com.hihops.hops.user.dto.UserSummaryResponse;

/** 관리자 계약의 필드만 선언한다. 담당자 상세를 직렬화한 뒤 필드를 제거하지 않는다. */
public record TicketManagementDetailResponse(UUID id, UUID inquiryId, UUID problemId, UUID workItemId,
        String ticketNumber, String title, String processingType, SupportedSystemResponse reportedSystem,
        SupportedSystemResponse responsibleSystem, UserSummaryResponse assignee, TicketState state,
        int ticketRevision, Instant createdAt, Instant updatedAt, UserSummaryResponse currentPrimary,
        List<AssignmentHistoryResponse> assignmentHistory) {
    public static TicketManagementDetailResponse of(TicketSummaryResponse summary, UserSummaryResponse primary,
            List<AssignmentHistoryResponse> history) {
        return new TicketManagementDetailResponse(summary.id(), summary.inquiryId(), summary.problemId(),
                summary.workItemId(), summary.ticketNumber(), summary.title(), summary.processingType(),
                summary.reportedSystem(), summary.responsibleSystem(), summary.assignee(), summary.state(),
                summary.ticketRevision(), summary.createdAt(), summary.updatedAt(), primary, history);
    }
}
