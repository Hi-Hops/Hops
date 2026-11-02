package com.hihops.hops.inquiry.domain;

/**
 * 메시지를 누가 보냈는지 (docs/ERD.md "대화 메시지 message" sender_type, specs/contracts/DATA_TYPES.md ChatMessage.sender).
 *
 * <p>화면은 이 값으로 말풍선을 사용자·AI로 나눠 보여준다. DB와 JSON 모두 이름 그대로 쓴다.
 */
public enum SenderType {
    /** 문의자 */
    USER,
    /** AI 상담 답변 */
    AI
}
