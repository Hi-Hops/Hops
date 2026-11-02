package com.hihops.hops.ticket.domain;

/** 티켓에 무슨 일이 있었는지 (docs/ERD.md "티켓 변경 이력 ticket_event" event_type). */
public enum TicketEventType {
    /** 생성 */
    CREATED,
    /** 처리 상태 변경 */
    STATUS_CHANGED,
    /** 처리 유형 변경·장애 확정 */
    WORK_TYPE_CHANGED,
    /** AI가 쓴 내용을 사람이 정정 */
    CONTENT_CORRECTED,
    /** 배정 후 문의자가 보낸 보완 정보 (새 티켓·재배정 없이 기존 티켓에 연결) */
    SUPPLEMENT_ADDED,
    /** 문의자가 해결을 확인함 */
    USER_CONFIRMED,
    /** 문의자가 아직 해결되지 않았다고 응답함 */
    USER_NOT_RESOLVED
}
