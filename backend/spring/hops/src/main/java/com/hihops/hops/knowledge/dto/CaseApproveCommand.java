package com.hihops.hops.knowledge.dto;

import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 사례 승인 요청 (DATA_TYPES CaseApproveCommand). 최신 DRAFT의 확인된 버전에만 승인한다. */
public record CaseApproveCommand(@NotNull UUID requestId, @NotNull @Min(1) Integer expectedCaseVersion) {
}
