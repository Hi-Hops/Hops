package com.hihops.hops.knowledge.domain;

/** 사례 상태 (docs/ERD.md "해결 사례 resolved_case" case_status). 초안은 검색에서 제외한다. */
public enum CaseStatus {
    /** Curator 초안 */
    DRAFT,
    /** 시스템 주담당자 승인 */
    APPROVED
}
