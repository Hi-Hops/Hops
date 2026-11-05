package com.hihops.hops.knowledge.dto;

import java.util.UUID;

import com.hihops.hops.knowledge.domain.IndexStore;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 색인 재시도 요청 (DATA_TYPES IndexRetryCommand). FAILED 저장소만 같은 작업의 다음 attempt로 다시 실행한다. */
public record IndexRetryCommand(@NotNull UUID requestId, @NotNull @Min(1) Integer expectedCaseVersion,
        @NotNull IndexStore store) {
}
