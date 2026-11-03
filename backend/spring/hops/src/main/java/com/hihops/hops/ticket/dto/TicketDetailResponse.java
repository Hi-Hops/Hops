package com.hihops.hops.ticket.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.hihops.hops.itsystem.dto.SupportedSystemResponse;
import com.hihops.hops.user.dto.UserSummaryResponse;

public record TicketDetailResponse(UUID id, UUID inquiryId, UUID problemId, UUID workItemId,
        String ticketNumber, String title, String processingType, SupportedSystemResponse reportedSystem,
        SupportedSystemResponse responsibleSystem, UserSummaryResponse assignee, TicketState state,
        int ticketRevision, Instant createdAt, Instant updatedAt, UserSummaryResponse currentPrimary,
        List<AssignmentHistoryResponse> assignmentHistory, StandardRequestResponse standardRequest,
        List<WorkRecordResponse> workRecords, ResolutionRequestResponse activeResolutionRequest,
        CaseGenerationResponse caseGeneration) {
    public static TicketDetailResponse of(TicketSummaryResponse summary, UserSummaryResponse primary,
            List<AssignmentHistoryResponse> history, StandardRequestResponse standard,
            List<WorkRecordResponse> records, ResolutionRequestResponse resolution, CaseGenerationResponse generation) {
        return new TicketDetailResponse(summary.id(), summary.inquiryId(), summary.problemId(), summary.workItemId(),
                summary.ticketNumber(), summary.title(), summary.processingType(), summary.reportedSystem(),
                summary.responsibleSystem(), summary.assignee(), summary.state(), summary.ticketRevision(),
                summary.createdAt(), summary.updatedAt(), primary, history, standard, records, resolution, generation);
    }
}
