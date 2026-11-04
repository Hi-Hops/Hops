package com.hihops.hops.realtime.dto;

import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * 문의 메시지 저장·정정 알림 (WIRE_CONTRACT 12.4 message.stored, DATA_TYPES 신규 절 MessageStoredEvent).
 *
 * <pre>
 * { "eventId": "UUID", "inquiryId": "UUID", "inquiryVersion": 2, "messageId": "UUID", "requestId": "UUID 또는 null" }
 * </pre>
 *
 * <p>메시지를 저장한 트랜잭션 안에서 발행한다. commit된 뒤에만 그 문의의 구독으로 전달된다(롤백되면 보내지 않는다).
 * 프론트는 이 알림을 GET 무효화 신호로만 쓰고 문의·메시지를 다시 조회한다. 본문은 싣지 않는다.
 *
 * @param inquiryVersion 저장 직후의 문의 버전. AI 답변은 입력 버전을 올리지 않으므로 그 실행의 버전이다
 * @param requestId      USER 메시지를 만든 명령 ID(정정이면 그 정정 요청). AI 메시지는 null
 */
public record MessageStoredEvent(UUID eventId, UUID inquiryId, int inquiryVersion, UUID messageId,
        @Nullable UUID requestId) {

    public MessageStoredEvent {
        Objects.requireNonNull(eventId);
        Objects.requireNonNull(inquiryId);
        Objects.requireNonNull(messageId);
    }

    public static MessageStoredEvent of(UUID inquiryId, int inquiryVersion, UUID messageId, @Nullable UUID requestId) {
        return new MessageStoredEvent(UUID.randomUUID(), inquiryId, inquiryVersion, messageId, requestId);
    }
}
