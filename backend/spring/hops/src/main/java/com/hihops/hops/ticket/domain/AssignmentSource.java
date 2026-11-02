package com.hihops.hops.ticket.domain;

/** 배정이 어떻게 생겼는지 (docs/ERD.md "담당 배정 이력 ticket_assignment" source). 오배정 검토에 쓴다. */
public enum AssignmentSource {
    /** 티켓 생성과 동시에 책임 시스템 주담당자에게 자동 배정 */
    AUTO,
    /** 담당자나 업무 관리자가 시스템·담당자를 수동 정정 */
    MANUAL_CORRECTION
}
