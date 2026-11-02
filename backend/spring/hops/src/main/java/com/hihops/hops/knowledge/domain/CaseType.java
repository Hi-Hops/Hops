package com.hihops.hops.knowledge.domain;

/** 사례 종류 (docs/ERD.md "해결 사례 resolved_case" case_type). 종류마다 승인 전제 조건이 다르다. */
public enum CaseType {
    /** 해결 사례: 문의자 해결 확인이 끝나야 승인할 수 있다 */
    RESOLUTION,
    /** 기능 구현 사례: IMPLEMENTED 처리 기록이 있어야 승인할 수 있다 */
    IMPLEMENTATION
}
