package com.hihops.hops.inquiry.domain;

/**
 * 메시지 상태 (docs/ERD.md "대화 메시지 message" message_status). 답변 생성 중·완료·실패를 화면에 구분해 보여준다.
 *
 * <p>DB에는 이름 그대로("COMPLETED") 저장된다. ERD에는 의미만 있고 값 이름은 없어서 의미를 그대로 옮겨 정했다.
 */
public enum MessageStatus {
    /** 답변 생성 중 */
    GENERATING,
    /** 완료 */
    COMPLETED,
    /** 실패 */
    FAILED
}
