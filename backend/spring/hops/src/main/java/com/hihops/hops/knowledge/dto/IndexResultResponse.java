package com.hihops.hops.knowledge.dto;

import java.time.Instant;
import java.util.UUID;

import com.hihops.hops.global.exception.ErrorResponse;

/** IndexResult (DATA_TYPES 신규 절). 저장소별 같은 승인 버전의 반영 시도. */
public record IndexResultResponse(UUID jobId, UUID caseId, int caseVersion, String store, String state, int attempt,
        ErrorResponse error, Instant updatedAt) {
}
