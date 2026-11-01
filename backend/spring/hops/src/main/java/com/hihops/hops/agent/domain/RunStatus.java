package com.hihops.hops.agent.domain;

/**
 * AI 실행 상태 (docs/ERD.md "AI 실행 agent_run" run_status). 실행이 어떻게 끝났는지 나타낸다.
 *
 * <p>주의: SUCCEEDED는 분석이 끝났다는 뜻이지, 티켓 저장 같은 업무 저장 성공이 아니다.
 */
public enum RunStatus {
    /** 실행 중 */
    RUNNING,
    /** 분석 완료 */
    SUCCEEDED,
    /** 실패 */
    FAILED,
    /** 시간 초과 */
    TIMED_OUT,
    /** 취소 */
    CANCELLED
}
