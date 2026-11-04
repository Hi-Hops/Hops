package com.hihops.hops.agent.internal;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/** STORED는 티켓과 현재 배정 이력이 함께 저장된 경우에만 반환한다. */
public record TicketCreationResult(String contractVersion, UUID runId, String problemRef, UUID workItemId,
        UUID problemId, int inquiryVersion, String outcome, @Nullable UUID ticketId,
        @Nullable UUID responsibleSystemId, @Nullable UUID assigneeId, int attempt, int maxAttempts,
        boolean retryable, @Nullable String errorCode, Instant updatedAt) { }
