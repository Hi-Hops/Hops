package com.hihops.hops.knowledge.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** CaseVersion (DATA_TYPES 신규 절). resolved_case 현재 행을 투영하며 id는 resolved_case.id다. */
public record CaseVersionResponse(UUID id, int caseVersion, String state, UUID ticketId, UUID problemId,
        UUID workRecordId, UUID resolutionRequestId, UUID systemId, String caseType, String problemStatement,
        String confirmedCause, String title, String action, String verification, String applicability,
        List<String> limitations, String appliesToSystemVersion, String accessScope, String origin,
        String confirmationOrigin, List<UUID> sourceMessageIds, Instant createdAt) {
}
