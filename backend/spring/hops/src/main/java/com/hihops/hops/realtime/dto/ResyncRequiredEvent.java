package com.hihops.hops.realtime.dto;

import java.util.Objects;
import java.util.UUID;

/** 재생 불가를 알리는 연결 단위 제어 이벤트. 수신자는 REST 복구 후 커서를 비우고 다시 연결한다. */
public record ResyncRequiredEvent(UUID eventId) {
    public ResyncRequiredEvent {
        Objects.requireNonNull(eventId);
    }
}
