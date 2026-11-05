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
 * 지식 검색 요청 (contracts/agents/KNOWLEDGE_TOOLS.md). 접근 범위·목적·사용자는 요청에 없다: 실행(runId)에서 Spring이 확정한다.
 * 알 수 없는 필드는 컨트롤러가 거부한다. {@code accessScope}·{@code purpose}를 실어 범위를 넓히려는 시도를 받아들이지 않기 위해서다.
 * {@code systemIds}는 실행의 접근 시스템을 좁히기만 한다.
 */
public record KnowledgeSearchRequest(
        @Positive int inquiryVersion,
        @NotBlank @Size(max = 100) @Pattern(regexp = "new:[A-Za-z0-9_-]+|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}") String problemRef,
        @NotBlank @Size(max = 4000) String query,
        @Min(1) @Max(5) Integer topK,
        @Size(min = 1, max = 4) List<@NotNull Kind> kinds,
        @Size(min = 1, max = 10) List<@NotNull UUID> systemIds) {

    /** 요청할 수 있는 문서 종류. 근거(KnowledgeEvidence)의 kind와 같은 이름이다. */
    public enum Kind {
        GUIDE, POLICY, RUNBOOK, CASE
    }

    public int effectiveTopK() {
        return topK == null ? 5 : topK;
    }
}
