package com.hihops.hops.knowledge.dto;

import java.util.UUID;

import com.hihops.hops.knowledge.domain.IndexStore;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 색인 확인 요청 (DATA_TYPES IndexCheckCommand). PENDING·RUNNING·UNKNOWN인 현재 요청을 확인한다. 새 작업·attempt를 만들지 않는다. */
public record IndexCheckCommand(@NotNull UUID requestId, @NotNull @Min(1) Integer expectedCaseVersion,
        @NotNull IndexStore store) {
}
