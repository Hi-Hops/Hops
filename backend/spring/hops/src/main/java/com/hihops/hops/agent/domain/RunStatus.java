package com.hihops.hops.agent.domain;

/**
 * AI 실행 상태 (docs/ERD.md "AI 실행 agent_run" run_status, 6.1절).
 *
 * <p>흐름: QUEUED → RUNNING → SUCCEEDED / FAILED / TIMED_OUT. 새 입력 버전으로 대체되면 CANCELLED이고,
 * 밖(API·SSE)으로는 SUPERSEDED로 바꿔 보낸다 (docs/DATA.md 11.2절).
 *
 * <p>주의: SUCCEEDED는 분석이 끝났다는 뜻이지, 티켓 저장 같은 업무 저장 성공이 아니다.
 */
public enum RunStatus {
    /** 접수됨, 아직 시작 전 (startedAt이 비어 있다) */
    QUEUED,
    /** 실행 중 */
    RUNNING,
    /** 분석 완료 */
    SUCCEEDED,
    /** 실패 */
    FAILED,
    /** 시간 초과 */
    TIMED_OUT,
    /** 새 입력 버전·실행으로 대체됨 (외부에는 SUPERSEDED) */
    CANCELLED
}
