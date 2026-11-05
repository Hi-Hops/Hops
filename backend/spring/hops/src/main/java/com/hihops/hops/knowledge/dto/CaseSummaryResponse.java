package com.hihops.hops.knowledge.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** CaseSummary (DATA_TYPES 신규 절). MVP의 publishedVersions는 [] 또는 현재 승인·공개 버전 하나다. */
public record CaseSummaryResponse(UUID id, String title, UUID systemId, int latestCaseVersion, String latestState,
        List<Integer> publishedVersions, String generationState, Instant updatedAt) {
}
