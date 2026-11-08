package com.hihops.hops.inquiry.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 문의자가 이미지 관찰별로 확인한 내용을 한 번에 보낸다. */
public record ImageObservationResponseCommand(@NotNull UUID requestId,
        @NotNull @Min(1) Integer expectedInquiryVersion,
        @NotNull @Size(min = 1, max = 15) List<@NotNull @Valid Response> responses) {
    public record Response(@NotNull UUID observationId,
            @NotNull @Min(1) @Max(Integer.MAX_VALUE - 1) Integer expectedObservationRevision,
            @NotNull Decision decision) { }

    public enum Decision { CONFIRMED, INCORRECT, UNKNOWN }
}
