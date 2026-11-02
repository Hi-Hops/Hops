package com.hihops.hops.ticket.domain;

/** 티켓을 바꾼 주체 (docs/ERD.md "티켓 변경 이력 ticket_event" actor_type). */
public enum ActorType {
    /** 사람 (담당자·문의자·업무 관리자). actorMember에 누구인지 남긴다 */
    MEMBER,
    /** AI. agentRun에 어느 실행인지 남긴다 */
    AI,
    /** 서버가 자동으로 처리 */
    SYSTEM
}
