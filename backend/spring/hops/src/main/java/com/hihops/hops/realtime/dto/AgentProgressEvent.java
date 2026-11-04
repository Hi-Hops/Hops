package com.hihops.hops.realtime.dto;

import java.util.UUID;

/** 임시 진행 표시다. 업무 저장·배정·해결 확정을 의미하지 않는다. */
public record AgentProgressEvent(UUID eventId, UUID inquiryId, int inquiryVersion, UUID runId,
        int stepNo, String role, String displaySummary) {
}
