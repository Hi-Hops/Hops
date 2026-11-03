package com.hihops.hops.global.idempotency;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * 변경 요청의 결과를 다시 확인하는 API (WIRE_CONTRACT 12.2 operations).
 *
 * <p>응답을 받지 못한 요청은 프론트가 이 API로 결과를 확인한다. 조회만 하며 업무를 다시 실행하지 않는다.
 * 요청자 본인의 기록만 보인다.
 */
@RestController
@RequestMapping("/operations") // 실제 주소는 /api/v2/operations (ApiPathConfig)
@RequiredArgsConstructor
public class OperationController {

    private final IdempotencyService idempotencyService;

    @GetMapping("/{operationId}")
    public CommandReceipt byOperationId(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID operationId) {
        return idempotencyService.findByOperationId(actorId(jwt), operationId);
    }

    @GetMapping("/by-request/{requestId}")
    public CommandReceipt byRequestId(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID requestId) {
        return idempotencyService.findByRequestId(actorId(jwt), requestId);
    }

    private static UUID actorId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject()); // sub = 회원 UUID (AccessTokenIssuer)
    }
}
