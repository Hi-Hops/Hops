package com.hihops.hops.inquiry.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 이미지 전체를 공개할 문제 목록을 문의자가 확인해 교체한다. 빈 목록은 공개 해제다. */
public record ImageProblemLinkCommand(@NotNull UUID requestId,
        @NotNull @Min(1) Integer expectedInquiryVersion,
        @NotNull @Min(0) @Max(Integer.MAX_VALUE - 1) Integer expectedLinkRevision,
        @NotNull @Size(max = 100) List<@NotNull UUID> problemIds,
        @NotNull Boolean confirmImageDisclosure) { }
