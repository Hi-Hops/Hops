package com.hihops.hops.agent.domain;

/** Supervisor가 고른 상담 경로 (docs/ERD.md "AI 실행 agent_run" route). Curator 실행에는 없다. */
public enum AgentRoute {
    /** 사용법 안내 */
    GUIDE,
    /** 신청·변경 요청 */
    REQUEST,
    /** 현상(오류·장애) 확인 */
    SYMPTOM
}
