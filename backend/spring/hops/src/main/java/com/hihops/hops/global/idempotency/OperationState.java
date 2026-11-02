package com.hihops.hops.global.idempotency;

/**
 * 요청으로 시작한 업무 작업의 처리 상태 (docs/ERD.md 6.1절 idempotency_key.operation_state).
 *
 * <p>같은 키로 다시 요청이 오면 이 상태를 먼저 보고 답한다. UNKNOWN은 실패로 단정하지 않고 저장 결과를 확인한다.
 */
public enum OperationState {
    /** 접수됨, 업무 반영 전 (응답 없음, 202 receipt) */
    PENDING,
    /** 업무 반영과 최종 응답 저장 완료 */
    SUCCEEDED,
    /** 검증·권한 등으로 거절됨 */
    REJECTED,
    /** 결과를 아직 확인하지 못함 */
    UNKNOWN
}
