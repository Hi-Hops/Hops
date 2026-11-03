package com.hihops.hops.ticket.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RelatedMessageRequest(@Pattern(regexp = "[1-9]|[1-9][0-9]|100") String size,
        @Size(min = 1, max = 160) String cursor) {
    public int pageSize() {
        return size == null ? 30 : Integer.parseInt(size);
    }
}
