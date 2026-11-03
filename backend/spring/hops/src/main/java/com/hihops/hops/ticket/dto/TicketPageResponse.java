package com.hihops.hops.ticket.dto;

import java.util.List;

/** Spring Page의 내부 필드를 공개하지 않고 합의된 네 필드만 반환한다. */
public record TicketPageResponse(List<TicketSummaryResponse> items, int page, int size, long totalElements) {
}
