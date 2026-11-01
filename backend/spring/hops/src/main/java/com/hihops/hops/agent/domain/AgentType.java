package com.hihops.hops.agent.domain;

/**
 * 실행 단계를 수행한 에이전트 종류 (docs/ERD.md "AI 실행 단계 agent_step" agent_type).
 *
 * <p>Diagnosis는 별도 에이전트가 아니라 Knowledge의 조건부 기능이라 여기 없다.
 * INQUERY는 기획서에서 회의로 정한 명칭을 그대로 쓴다 (오타 아님).
 */
public enum AgentType {
    /** 상담 경로를 고르는 총괄 에이전트 */
    SUPERVISOR,
    /** 문의 내용을 확인하고 추가 질문을 만드는 에이전트 */
    INQUERY,
    /** 가이드·사례 검색과 조건부 진단 */
    KNOWLEDGE,
    /** 표준 요청·티켓 제안 */
    TICKET,
    /** 해결 기록으로 사례 초안을 만드는 에이전트 */
    CURATOR
}
