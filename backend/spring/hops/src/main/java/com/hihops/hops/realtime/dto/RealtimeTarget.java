package com.hihops.hops.realtime.dto;

import java.util.UUID;

public record RealtimeTarget(Type type, UUID id) {
    public enum Type {
        INQUIRY, DEPARTMENT_TICKETS, TICKET
    }
}
