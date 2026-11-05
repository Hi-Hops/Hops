package com.hihops.hops.knowledge.dto;

import java.time.Instant;
import java.util.UUID;

import com.hihops.hops.user.dto.UserSummaryResponse;

/** CaseApproval (DATA_TYPES 신규 절). 승인한 해당 시스템 주담당자와 시각. */
public record CaseApprovalResponse(UUID caseId, int caseVersion, UserSummaryResponse approvedBy, Instant approvedAt) {
}
