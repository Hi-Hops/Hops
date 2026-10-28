package com.hihops.hops.realtime.dto;

import java.util.Objects;
import java.util.UUID;

/** DB 변경 트랜잭션 안에서 발행한다. 커밋된 뒤에만 SSE로 전달된다. */
public record StateChangedEvent(UUID eventId, Target target, Integer inquiryVersion) {
    public StateChangedEvent {
        Objects.requireNonNull(eventId);
        Objects.requireNonNull(target);
        // 부서 목록 변경은 특정 문의의 버전이 아니므로 숫자를 넣지 않는다.
        if (inquiryVersion != null) {
            throw new IllegalArgumentException("부서 대상 이벤트의 문의 버전은 null이어야 합니다.");
        }
    }

    /** 이번 기반 구현에서 지원하는 부서 목록 대상. 문의·티켓은 도메인 권한 연결 후 추가한다. */
    public record Target(RealtimeTarget.Type type, UUID id) {
        public Target {
            Objects.requireNonNull(id);
            if (type != RealtimeTarget.Type.DEPARTMENT_TICKETS) {
                throw new IllegalArgumentException("부서 티켓 목록 대상만 지원합니다.");
            }
        }
    }
}
