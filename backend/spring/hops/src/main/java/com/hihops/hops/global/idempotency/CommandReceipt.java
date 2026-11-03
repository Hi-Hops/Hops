package com.hihops.hops.global.idempotency;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.hihops.hops.global.exception.ErrorResponse;

/**
 * 변경 요청(문의 생성·메시지 전송 등)의 처리 결과 (DATA_TYPES 신규 절 CommandReceipt, WIRE_CONTRACT 12.1).
 *
 * <pre>
 * { "requestId": "UUID", "operationId": "UUID", "state": "SUCCEEDED",
 *   "resource": { "type": "INQUIRY", "id": "UUID" }, "error": null }
 * </pre>
 *
 * <p>SUCCEEDED는 "이 명령의 업무 저장이 끝났다"는 뜻이다. AI 답변·해결·검색 공개 완료가 아니다.
 * 프론트는 resource를 다시 GET해서 최신 상태를 읽는다.
 *
 * @param requestId   요청 본문의 requestId (= Idempotency-Key)
 * @param operationId 서버가 이 요청에 붙인 작업 번호. GET /operations/{operationId}로 다시 확인한다
 * @param state       처리 상태
 * @param resource    저장한 업무 대상. SUCCEEDED면 필수, PENDING/UNKNOWN이면 없을 수 있다
 * @param error       REJECTED일 때의 오류. 그 밖에는 null
 */
public record CommandReceipt(UUID requestId, UUID operationId, OperationState state, @Nullable ResourceRef resource,
        @Nullable ErrorResponse error) {

    public static CommandReceipt succeeded(UUID requestId, UUID operationId, ResourceRef resource) {
        return new CommandReceipt(requestId, operationId, OperationState.SUCCEEDED, resource, null);
    }
}
