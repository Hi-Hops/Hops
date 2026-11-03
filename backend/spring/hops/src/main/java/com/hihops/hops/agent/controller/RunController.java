package com.hihops.hops.agent.controller;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hihops.hops.agent.dto.RunSummaryResponse;
import com.hihops.hops.agent.service.RunQueryService;

import lombok.RequiredArgsConstructor;

/**
 * 상담 실행 조회 API (WIRE_CONTRACT 12.2 GET /runs/{runId}).
 *
 * <p>채팅은 실행이 QUEUED/RUNNING일 때만 1초 간격으로 이 API를 조회하고, 종료를 보면 문의와 메시지를 다시 읽는다 (12.8 7번).
 */
@RestController
@RequestMapping("/runs") // 실제 주소는 /api/v2/runs (ApiPathConfig)
@RequiredArgsConstructor
public class RunController {

    private final RunQueryService runQueryService;

    @GetMapping("/{runId}")
    public RunSummaryResponse run(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID runId) {
        return runQueryService.findMyRun(UUID.fromString(jwt.getSubject()), runId); // sub = 회원 UUID (AccessTokenIssuer)
    }
}
