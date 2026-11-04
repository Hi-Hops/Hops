package com.hihops.hops.realtime.dto;

import java.util.List;
import java.util.UUID;

/** 업무 거래의 내부 신호. 공개 이벤트 ID·순번은 commit 후에 생성한다. */
public record TicketChangedEvent(UUID ticketId, List<UUID> systemIds) {
    public TicketChangedEvent { systemIds = List.copyOf(systemIds); }
}
