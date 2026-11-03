package com.hihops.hops.ticket.dto;

import com.hihops.hops.ticket.domain.TicketStatus;

/** DATA11.2의 DB 상태와 공개 상태 변환. DB enum은 변경하지 않는다. */
public enum TicketState {
    WAITING(TicketStatus.OPEN),
    IN_PROGRESS(TicketStatus.IN_PROGRESS),
    WAITING_CONFIRMATION(TicketStatus.WAITING_CONFIRM),
    RESOLVED(TicketStatus.RESOLVED);

    private final TicketStatus databaseStatus;

    TicketState(TicketStatus databaseStatus) {
        this.databaseStatus = databaseStatus;
    }

    public TicketStatus databaseStatus() {
        return databaseStatus;
    }

    public static TicketState from(TicketStatus status) {
        return switch (status) {
            case OPEN -> WAITING;
            case IN_PROGRESS -> IN_PROGRESS;
            case WAITING_CONFIRM -> WAITING_CONFIRMATION;
            case RESOLVED -> RESOLVED;
        };
    }
}
