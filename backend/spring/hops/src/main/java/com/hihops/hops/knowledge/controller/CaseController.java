package com.hihops.hops.knowledge.controller;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import com.hihops.hops.global.dto.PageResponse;
import com.hihops.hops.global.idempotency.*;
import com.hihops.hops.knowledge.domain.CaseStatus;
import com.hihops.hops.knowledge.dto.CaseCommands.*;
import com.hihops.hops.knowledge.service.CaseService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/cases")
@RequiredArgsConstructor
public class CaseController {
    private final CaseService cases;
    @GetMapping
    public PageResponse<JsonNode> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) UUID systemId,
            @RequestParam(required = false) CaseStatus state, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) { return cases.list(jwt, systemId, state, page, size); }
    @GetMapping("/{id}")
    public JsonNode detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestParam(required = false) Integer caseVersion) { return cases.detail(jwt, id, caseVersion); }
    @PostMapping("/{id}/versions")
    public ResponseEntity<CommandReceipt> edit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody Edit body, HttpServletRequest http) {
        return cases.edit(jwt, id, IdempotentRequest.of(jwt, http, key, body.requestId(), body), body).toResponseEntity();
    }
    @PostMapping("/{id}/approvals")
    public ResponseEntity<CommandReceipt> approve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody Approve body, HttpServletRequest http) {
        return cases.approve(jwt, id, IdempotentRequest.of(jwt, http, key, body.requestId(), body), body).toResponseEntity();
    }
    @PostMapping("/{id}/index-checks")
    public ResponseEntity<CommandReceipt> check(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody Index body, HttpServletRequest http) {
        return cases.index(jwt, id, IdempotentRequest.of(jwt, http, key, body.requestId(), body), body, false).toResponseEntity();
    }
    @PostMapping("/{id}/index-retries")
    public ResponseEntity<CommandReceipt> retry(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody Index body, HttpServletRequest http) {
        return cases.index(jwt, id, IdempotentRequest.of(jwt, http, key, body.requestId(), body), body, true).toResponseEntity();
    }
}
