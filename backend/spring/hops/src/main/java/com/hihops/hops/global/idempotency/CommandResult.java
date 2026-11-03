package com.hihops.hops.global.idempotency;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * {@link IdempotencyService#execute}의 결과. 응답할 HTTP 상태와 본문을 함께 갖는다.
 *
 * @param status  200(업무 저장 확정) 또는 202(접수만 됐거나 결과 미확인)
 * @param receipt 응답 본문
 */
public record CommandResult(HttpStatus status, CommandReceipt receipt) {

    /** Controller에서 그대로 반환한다. */
    public ResponseEntity<CommandReceipt> toResponseEntity() {
        return ResponseEntity.status(status).body(receipt);
    }
}
