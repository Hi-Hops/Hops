package com.hihops.hops.knowledge.dto;

import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.hihops.hops.global.validation.UserContent;
import jakarta.validation.constraints.*;

/** hops-system-1의 현재 초안·승인·수동 반영 명령. */
public final class CaseCommands {
    private CaseCommands() {}
    public enum Scope { EMPLOYEE, SYSTEM_STAFF }
    public enum Store { VECTOR, NEO4J }
    public record Edit(@NotNull UUID requestId, @NotNull @Min(1) Integer expectedCaseVersion,
            @NotBlank @UserContent @Size(max = 200) String title, @NotBlank @UserContent String problemStatement,
            @NotBlank @UserContent String action, @NotBlank @UserContent String verification, @NotBlank @UserContent String applicability,
            @NotNull @Size(max = 100) List<@NotBlank @Size(max = 4000) String> limitations,
            @JsonProperty(required = true) @UserContent @Size(max = 100) String appliesToSystemVersion,
            @NotNull Scope accessScope) {}
    public record Approve(@NotNull UUID requestId, @NotNull @Min(1) Integer expectedCaseVersion) {}
    public record Index(@NotNull UUID requestId, @NotNull @Min(1) Integer expectedCaseVersion,
            @NotNull Store store) {}
}
