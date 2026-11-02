package com.hihops.hops.inquiry.domain;

/** 사용자가 무엇을 원하는지 (docs/ERD.md "표준 요청서 standard_request" intent). */
public enum RequestIntent {
    /** 사용법 질문 */
    HOW_TO,
    /** 권한 신청 */
    ACCESS_REQUEST,
    /** 오류·이상 현상 신고 */
    OBSERVED_SYMPTOM,
    /** 새 기능 요청 */
    FEATURE_REQUEST,
    /** 기존 기능 개선 요청 */
    IMPROVEMENT_REQUEST,
    /** 아직 의도를 알 수 없음 */
    UNKNOWN,
    /** 지원 범위 밖 */
    OUT_OF_SCOPE
}
