package com.hihops.hops.global.idempotency;

/**
 * 변경 요청이 저장한 업무 대상의 종류 (DATA_TYPES 신규 절 ResourceType).
 *
 * <p>{@link CommandReceipt#resource()}에 담겨 "무엇을 다시 조회하면 되는지" 알려준다.
 * 예: 메시지 저장은 MESSAGE가 아니라 INQUIRY를 돌려준다. 프론트는 그 문의를 GET해서 최신 상태를 읽는다.
 */
public enum ResourceType {
    INQUIRY,
    MESSAGE,
    RUN,
    TICKET,
    WORK_ITEM,
    WORK_RECORD,
    RESOLUTION_REQUEST,
    CASE,
    INDEX_JOB,
    CASE_GENERATION
}
