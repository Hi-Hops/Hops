package com.hihops.hops.inquiry.domain;

/**
 * 문의 종합 상태 (docs/ERD.md "문의 inquiry" inquiry_status). 내 문의 목록의 상태 필터와 표시에 쓴다.
 *
 * <p>DB에는 이름 그대로("IN_PROGRESS") 저장된다. 문제(문의 문제)별 세부 진행은 inquiry_problem.problem_status가 따로 맡는다.
 */
public enum InquiryStatus {
    /** 진행 중 */
    IN_PROGRESS,
    /** 문의자 답변·확인을 기다리는 중 */
    WAITING_USER,
    /** 해결됨 */
    RESOLVED
}
