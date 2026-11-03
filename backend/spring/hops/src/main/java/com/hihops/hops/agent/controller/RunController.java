package com.hihops.hops.agent.controller;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hihops.hops.agent.dto.RunRetryCommand;
import com.hihops.hops.agent.dto.RunSummaryResponse;
import com.hihops.hops.agent.service.RunCommandService;
import com.hihops.hops.agent.service.RunQueryService;
import com.hihops.hops.global.idempotency.CommandReceipt;
import com.hihops.hops.global.idempotency.IdempotentRequest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 상담 실행 조회·재분석 API (WIRE_CONTRACT 12.2 GET /runs/{runId}, POST /runs/{runId}/retries).
 *
 * <p>채팅은 실행이 QUEUED/RUNNING일 때만 1초 간격으로 조회하고, 종료를 보면 문의와 메시지를 다시 읽는다 (12.8 7번).
 * 실패 오류의 retryable=true이면 "다시 분석"으로 재분석을 요청한다. 재분석은 Idempotency-Key 헤더와 본문 requestId가 같아야 한다.
 */
@RestController
@RequestMapping("/runs") // 실제 주소는 /api/v2/runs (ApiPathConfig)
@RequiredArgsConstructor
public class RunController {

    private final RunQueryService runQueryService;
    private final RunCommandService runCommandService;

    @GetMapping("/{runId}")
    public RunSummaryResponse run(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID runId) {
        return runQueryService.findMyRun(UUID.fromString(jwt.getSubject()), runId); // sub = 회원 UUID (AccessTokenIssuer)
    }

    @PostMapping("/{runId}/retries")
    public ResponseEntity<CommandReceipt> retry(@AuthenticationPrincipal Jwt jwt, HttpServletRequest http,
            @PathVariable UUID runId,
            @RequestHeader(value = IdempotentRequest.HEADER, required = false) @Nullable String idempotencyKey,
            @Valid @RequestBody RunRetryCommand command) {
        IdempotentRequest request = IdempotentRequest.of(jwt, http, idempotencyKey, command.requestId(), command);
        return runCommandService.retry(request, runId, command).toResponseEntity();
    }
}
