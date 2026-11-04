package com.hihops.hops.agent.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import com.hihops.hops.knowledge.service.KnowledgeAuthorizationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

/** 서비스 토큰 인증 뒤 저장된 run의 현재 권한으로 검색 후보만 허용한다. */
@RestController
@RequestMapping("/internal/v2/knowledge/authorizations")
@RequiredArgsConstructor
public class KnowledgeAuthorizationController {
    public enum Kind { GUIDE, POLICY, RUNBOOK, CASE }
    public record Candidate(@NotNull UUID documentId, @Min(1) int documentVersion, @NotNull Kind kind) {}
    public record Command(@NotNull @Pattern(regexp = "hops-system-1") String contractVersion,
            @NotNull UUID requestId, @NotNull UUID runId, @Min(1) int policyRevision,
            @NotNull @Size(max = 20) List<@Valid Candidate> candidates) {}
    private final KnowledgeAuthorizationService authorizations;
    @PostMapping public JsonNode authorize(@Valid @RequestBody Command command) { return authorizations.authorize(command); }
}
