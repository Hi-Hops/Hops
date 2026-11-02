package com.hihops.hops.inquiry.domain;

/**
 * 문의 문제의 진행 상태 (docs/ERD.md "문의 문제 inquiry_problem" problem_status). 문제마다 어디까지 왔는지 구분한다.
 *
 * <p>DB에는 이름 그대로 저장된다. 문의 전체의 종합 상태는 {@link InquiryStatus}가 따로 맡는다.
 */
public enum ProblemStatus {
    /** 추가 질문 중 */
    CLARIFYING,
    /** 질문 5회 뒤에도 시스템을 몰라 지원 시스템 선택을 기다리는 중. 배정하지 않고 보존한다 */
    SYSTEM_PENDING,
    /** 직원 가이드로 안내한 뒤 문의자의 해결 확인을 기다리는 중 */
    GUIDED,
    /** 문의자가 티켓 없이 해결됐다고 확인함 */
    RESOLVED_WITHOUT_TICKET,
    /** 티켓 생성 (배정됨) */
    TICKET_CREATED,
    /** 티켓 생성 실패. 오류 코드를 함께 남긴다 */
    TICKET_FAILED,
    /** 지원 범위 밖. 범위를 안내하고 티켓을 만들지 않는다 */
    OUT_OF_SCOPE
}
