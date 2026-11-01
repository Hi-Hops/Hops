package com.hihops.hops.notification.domain;

/** 알림 종류 (docs/ERD.md "알림 notification" notification_type). 받는 사람과 이동할 화면을 구분한다. */
public enum NotificationType {
    /** 담당자: 새 티켓이 배정됨 */
    TICKET_ASSIGNED,
    /** 문의자: 해결됐는지 확인해 달라는 요청 */
    CONFIRM_REQUESTED,
    /** 문의자: 기능 구현 결과 통보 */
    RESULT_NOTIFIED,
    /** 담당자: Curator가 만든 사례 초안 승인 요청 */
    CASE_APPROVAL_REQUESTED
}
