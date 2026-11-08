package com.hihops.hops.inquiry.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 이미지의 분석 대상 문제를 교체한다. 담당자 공개는 별도 명령으로 관리한다. */
public record ImageAnalysisTargetCommand(@NotNull UUID requestId,
        @NotNull @Min(1) Integer expectedInquiryVersion,
        @NotNull @Min(0) @Max(Integer.MAX_VALUE - 1) Integer expectedAnalysisRevision,
        @NotNull @Size(max = 100) List<@NotNull UUID> problemIds) { }
