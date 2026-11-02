package com.hihops.hops.agent.domain;

/** AI 실행의 작업 종류 (docs/ERD.md "AI 실행 agent_run" task). 상담과 사후 지식화를 나눠 관리한다. */
public enum AgentTask {
    /** 사용자 상담 분석 (Supervisor → 전문 에이전트) */
    INQUIRY_ANALYSIS,
    /** 해결·구현 기록으로 사례 초안을 만드는 Curator 실행 */
    CURATION
}
