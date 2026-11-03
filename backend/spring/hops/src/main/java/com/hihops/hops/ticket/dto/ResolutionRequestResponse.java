package com.hihops.hops.ticket.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 서버가 저장한 확인 메타데이터만 투영한다. 티켓 상태로 요청 ID·원 기록을 추정하지 않는다. */
public record ResolutionRequestResponse(UUID id, UUID inquiryId, UUID problemId, UUID ticketId, String source,
        UUID sourceId, int sourceVersion, int requestRevision, String state, String resultSummary,
        String confirmationOrigin, Instant createdAt, Instant respondedAt) {
    public ResolutionRequestResponse {
        if (id == null || inquiryId == null || problemId == null || sourceId == null || sourceVersion < 1
                || !List.of("GUIDE", "WORK_RECORD").contains(source)
                || !List.of("PENDING", "RESOLVED", "UNRESOLVED").contains(state)
                || resultSummary == null || resultSummary.isBlank() || createdAt == null
                || !List.of("SYNTHETIC_PRESET", "HOPS_USER").contains(confirmationOrigin)
                || ("PENDING".equals(state) ? requestRevision != 1 || respondedAt != null
                        : requestRevision != 2 || respondedAt == null)) {
            throw new IllegalArgumentException("확인 요청 메타데이터 오류");
        }
    }
}
