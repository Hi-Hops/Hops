package com.hihops.hops.agent.internal;

import java.util.List;
import java.util.UUID;

import com.hihops.hops.agent.dto.AiRunRequest.StandardRequestBody;
import com.hihops.hops.inquiry.domain.RequestIntent;
import com.hihops.hops.inquiry.domain.WorkType;
import com.hihops.hops.ticket.dto.StandardRequestResponse.EvidenceStatementResponse;
import com.hihops.hops.ticket.dto.StandardRequestResponse.RequestDetailsResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** 사용자·담당자·티켓 ID는 AI가 지정하지 않는다. runId는 런타임이 고정한 경로로만 받는다. */
public record TicketCreationCommand(@Positive int inquiryVersion,
        @NotNull @Valid Problem problem, @NotNull @Valid Proposal proposal,
        @NotNull @Size(max = 100) List<@NotNull @Valid MessageLink> messageLinks) {
    public record MessageLink(@NotNull UUID messageId, @Positive int revision,
            @NotNull @Size(min = 1, max = 20) List<@NotNull @Valid Span> spans) { }
    public record Span(@PositiveOrZero int start, @Positive int end) { }
    public record Problem(
            @NotBlank @Size(max = 100) @Pattern(regexp = "new:[A-Za-z0-9_-]+|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}") String problemRef,
            @NotBlank @Size(max = 4000) String summary, @NotNull RequestIntent intent,
            @NotNull WorkType processingType, @NotNull UUID reportedSystemCandidateId,
            @NotNull @Size(max = 100) List<@NotNull EvidenceStatementResponse> facts,
            @NotNull @Size(max = 100) List<@NotNull EvidenceStatementResponse> assumptions,
            @NotNull @Size(max = 100) List<@NotBlank String> unknowns,
            @NotNull RequestDetailsResponse details) { }

    public record Proposal(@NotBlank String problemRef, @NotBlank @Size(max = 200) String title,
            @NotNull WorkType processingType, @NotNull UUID responsibleSystemCandidateId,
            @NotNull @Size(max = 100) List<@NotNull UUID> evidenceIds,
            @NotNull StandardRequestBody standardRequestSnapshot) { }

    public record Retry(@Positive int inquiryVersion, @Positive int expectedAttempt) { }
}
