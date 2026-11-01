package com.hihops.hops.ticket.domain;

/**
 * 티켓 처리 상태 (docs/ERD.md "티켓 ticket" ticket_status). 담당자 목록 필터와 해결 확인 흐름에 쓴다.
 *
 * <p>흐름: OPEN → IN_PROGRESS → WAITING_CONFIRM → RESOLVED.
 * 문의자가 미해결이라고 하면 같은 티켓을 IN_PROGRESS로 되돌린다. 완료 후 재오픈은 없다.
 */
public enum TicketStatus {
    /** 접수·배정됨 */
    OPEN,
    /** 처리 중 */
    IN_PROGRESS,
    /** 해결·구현 기록 후 문의자 확인 대기 */
    WAITING_CONFIRM,
    /** 문의자가 확인해 최종 완료 */
    RESOLVED
}
