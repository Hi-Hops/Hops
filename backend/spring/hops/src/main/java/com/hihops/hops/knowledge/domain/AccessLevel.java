package com.hihops.hops.knowledge.domain;

/**
 * 지식의 공개 범위 (docs/ERD.md "지식 문서 knowledge_document"·"해결 사례 resolved_case" access_level).
 *
 * <p>검색하기 전에 이 값으로 걸러서, 직원에게는 담당자 전용 자료가 보이지 않게 한다.
 */
public enum AccessLevel {
    /** 모든 직원 */
    EMPLOYEE,
    /** 시스템 담당자만 */
    STAFF
}
