package com.hihops.hops.ticket.domain;

/**
 * 티켓의 처리 유형 (docs/ERD.md "티켓 ticket" work_type).
 *
 * <p>표준 요청서의 {@link com.hihops.hops.inquiry.domain.WorkType}(7개)에서 티켓을 만들지 않는
 * KNOWLEDGE_QA·OUT_OF_SCOPE를 뺀 5개다. enum을 따로 두어 티켓에 그 두 값이 들어가는 실수를 컴파일 단계에서 막는다.
 * 장애(INCIDENT) 확정은 담당자가 INVESTIGATION을 조사한 뒤 바꾼다.
 */
public enum TicketWorkType {
    /** 권한 부여 같은 신청 처리 */
    SERVICE_REQUEST,
    /** 원인 확인이 필요한 현상 조사 */
    INVESTIGATION,
    /** 장애 (담당자 확정) */
    INCIDENT,
    /** 기능 변경·개선 */
    CHANGE_REQUEST,
    /** 사람이 검토해야 판단할 수 있음 */
    REVIEW_REQUIRED
}
