package com.hihops.hops.inquiry.dto;

import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 이미지 분석 대상을 변경한 뒤 현재 입력으로 새 실행을 접수한다. */
public record ImageAnalysisRunCommand(@NotNull UUID requestId,
        @NotNull @Min(1) Integer expectedInquiryVersion,
        @NotNull @Min(0) Integer expectedAnalysisRevision) { }
