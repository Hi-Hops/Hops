package com.hihops.hops.knowledge.domain;

/** 지식 문서 종류 (docs/ERD.md "지식 문서 knowledge_document" document_type). 검색 범위를 나누는 데 쓴다. */
public enum DocumentType {
    /** 직원용 사용법 안내 */
    EMPLOYEE_GUIDE,
    /** 업무 규칙·정책 */
    POLICY,
    /** 담당자용 처리 절차 */
    STAFF_RUNBOOK,
    /** 승인된 현재 해결 사례의 검색용 문서 (원본·승인 여부는 resolved_case에서 확인) */
    CASE
}
