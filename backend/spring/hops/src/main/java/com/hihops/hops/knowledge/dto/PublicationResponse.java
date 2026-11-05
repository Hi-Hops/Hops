package com.hihops.hops.knowledge.dto;

import java.util.UUID;

/** Publication (DATA_TYPES 신규 절). state는 승인·두 반영 성공의 전역 사실, canSearch는 현재 사용자 권한까지 본 결과. */
public record PublicationResponse(UUID caseId, int caseVersion, String state, boolean canSearch) {
}
