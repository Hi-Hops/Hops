package com.hihops.hops.ticket.domain;

/**
 * 처리 기록 종류 (docs/ERD.md "티켓 처리 기록 ticket_work_log" log_type).
 *
 * <p>RESOLVED·IMPLEMENTED가 저장되면 티켓은 문의자 해결 확인 대기로 바뀌고 Curator가 사례 초안을 만든다.
 * PROGRESS·FAILED_ATTEMPT는 처리 이력으로만 보존하고 사례를 만들지 않는다 (실패 사례 지식은 만들지 않음).
 */
public enum WorkLogType {
    /** 진행 중 조치 */
    PROGRESS,
    /** 실패한 시도 */
    FAILED_ATTEMPT,
    /** 해결 완료 */
    RESOLVED,
    /** 기능 구현 완료 */
    IMPLEMENTED
}
