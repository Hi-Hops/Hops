package com.hihops.hops.ticket.dto;

import java.util.UUID;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Pattern;

/** 생략과 빈 문자열을 구분한다. 잘못된 필터를 기본값으로 보정하지 않는다(WIRE12.2). */
public record TicketListRequest(
        @Pattern(regexp = "ASSIGNED_SYSTEMS|ADMIN") String scope,
        @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String systemId,
        @Pattern(regexp = "WAITING|IN_PROGRESS|WAITING_CONFIRMATION|RESOLVED") String state,
        @Pattern(regexp = "0|[1-9][0-9]*") @DecimalMax("2147483647") String page,
        @Pattern(regexp = "[1-9]|[1-9][0-9]|100") String size) {
    public TicketScope resolvedScope() {
        return scope == null ? TicketScope.ASSIGNED_SYSTEMS : TicketScope.valueOf(scope);
    }

    public UUID resolvedSystemId() {
        return systemId == null ? null : UUID.fromString(systemId);
    }

    public int pageNumber() {
        return page == null ? 0 : Integer.parseInt(page);
    }

    public int pageSize() {
        return size == null ? 20 : Integer.parseInt(size);
    }
}
