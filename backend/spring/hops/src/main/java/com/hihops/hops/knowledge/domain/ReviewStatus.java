package com.hihops.hops.knowledge.domain;

/**
 * 지식 문서 검수 상태 (docs/ERD.md "지식 문서 knowledge_document" review_status). 사람이 검수한 문서만 검색에 쓴다.
 *
 * <p>ERD에는 기본값 PENDING과 "APPROVED만 사용"만 나와 있어 두 값만 둔다.
 */
public enum ReviewStatus {
    /** 검수 대기 */
    PENDING,
    /** 검수 승인 (검색 사용) */
    APPROVED
}
