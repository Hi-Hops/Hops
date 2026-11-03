package com.hihops.hops.ticket.dto;

import java.util.List;

public record RelatedMessagePageResponse(List<RelatedMessageResponse> items, String nextCursor) {
}
