package com.hihops.hops.agent.internal;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 관계 조회 요청 (contracts/agents/KNOWLEDGE_TOOLS.md "관계 조회"). 접근 범위는 요청에 없고 실행(runId)에서 확정한다.
 * 알 수 없는 필드는 {@link StrictRequestParser}가 거부한다. keywords와 featureIds 중 하나는 있어야 한다.
 */
public record RelationSearchRequest(
        @Positive int inquiryVersion,
        @NotBlank @Size(max = 100) @Pattern(regexp = "new:[A-Za-z0-9_-]+|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}") String problemRef,
        @Size(min = 1, max = 20) List<@NotBlank @Size(max = 50) String> keywords,
        @Size(min = 1, max = 10) List<@NotNull UUID> featureIds,
        @Size(min = 1, max = 10) List<@NotNull UUID> systemIds,
        @Min(1) @Max(5) Integer limit) {

    public int effectiveLimit() {
        return limit == null ? 5 : limit;
    }
}
