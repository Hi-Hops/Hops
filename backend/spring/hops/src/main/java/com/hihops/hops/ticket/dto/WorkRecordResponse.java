package com.hihops.hops.ticket.dto;

import java.time.Instant;
import java.util.UUID;

import com.hihops.hops.ticket.domain.TicketWorkLog;
import com.hihops.hops.ticket.domain.WorkLogType;
import com.hihops.hops.user.dto.UserSummaryResponse;

public record WorkRecordResponse(UUID id, UUID ticketId, String kind, String action, String verification,
        String confirmedCause, String appliesToSystemVersion, UserSummaryResponse author, Instant createdAt) {
    /** 처리 기록 종류의 계약 이름. 화면 응답과 AI 입력(CASE_DRAFT)이 같은 이름을 쓰도록 한 곳에서 정한다. */
    public static String kind(WorkLogType type) {
        return switch (type) {
            case PROGRESS -> "PROGRESS";
            case FAILED_ATTEMPT -> "FAILED_ATTEMPT";
            case RESOLVED -> "VERIFIED_RESOLUTION";
            case IMPLEMENTED -> "IMPLEMENTED_VERIFIED";
        };
    }

    public static WorkRecordResponse from(TicketWorkLog value) {
        return new WorkRecordResponse(value.getId(), value.getTicket().getId(), kind(value.getLogType()), value.getActionDetail(),
                value.getVerificationResult(), value.getConfirmedCause(), value.getAppliedVersion(),
                UserSummaryResponse.from(value.getAuthor()), value.getCreatedAt());
    }
}
