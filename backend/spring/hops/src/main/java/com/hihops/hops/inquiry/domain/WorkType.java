package com.hihops.hops.inquiry.domain;

/**
 * 담당자가 어떤 업무를 할지 (docs/ERD.md "표준 요청서 standard_request" work_type).
 *
 * <p>KNOWLEDGE_QA(가이드로 답하는 질문)와 OUT_OF_SCOPE는 티켓을 만들지 않는다.
 * 티켓의 처리 유형(ticket.work_type)은 이 중 나머지 5개만 쓴다.
 */
public enum WorkType {
    /** 가이드·지식으로 답할 수 있는 질문 (티켓 없음) */
    KNOWLEDGE_QA,
    /** 권한 부여 같은 신청 처리 */
    SERVICE_REQUEST,
    /** 원인 확인이 필요한 현상 조사 */
    INVESTIGATION,
    /** 장애. 담당자가 조사 후 확정한다 */
    INCIDENT,
    /** 기능 변경·개선 */
    CHANGE_REQUEST,
    /** 사람이 검토해야 판단할 수 있음 */
    REVIEW_REQUIRED,
    /** 지원 범위 밖 (티켓 없음) */
    OUT_OF_SCOPE
}
