package com.hihops.hops.global.idempotency;

/**
 * 변경 요청이 저장한 업무 대상의 종류 (DATA_TYPES 신규 절 ResourceType).
 *
 * <p>{@link CommandReceipt#resource()}에 담겨 "무엇을 다시 조회하면 되는지" 알려준다.
 * 예: 메시지 저장은 MESSAGE가 아니라 INQUIRY를 돌려준다. 프론트는 그 문의를 GET해서 최신 상태를 읽는다.
 *
 * <p>주의: 값은 추가만 한다. idempotency_key.resource_type과 저장된 영수증 JSON에 이름이 남아 있어,
 * 지우거나 이름을 바꾸면 보존 중인 결과를 다시 읽지 못한다.
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
    CASE_GENERATION,
    IMAGE_ATTACHMENT
}
