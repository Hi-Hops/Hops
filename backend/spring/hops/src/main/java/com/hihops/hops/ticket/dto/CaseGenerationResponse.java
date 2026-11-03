package com.hihops.hops.ticket.dto;

import java.util.UUID;

import com.hihops.hops.global.exception.ErrorResponse;

public record CaseGenerationResponse(UUID id, UUID workRecordId, String state, UUID caseId, ErrorResponse error) {
}
