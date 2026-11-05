package com.hihops.hops.knowledge.dto;

import java.util.UUID;

import com.hihops.hops.knowledge.domain.CaseStatus;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Pattern;

/** GET /cases 조건. 생략과 빈 문자열을 구분하고 잘못된 필터를 기본값으로 보정하지 않는다(WIRE12.2). */
public record CaseListRequest(
        @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String systemId,
        @Pattern(regexp = "DRAFT|APPROVED") String state,
        @Pattern(regexp = "0|[1-9][0-9]*") @DecimalMax("2147483647") String page,
        @Pattern(regexp = "[1-9]|[1-9][0-9]|100") String size) {

    public UUID resolvedSystemId() {
        return systemId == null ? null : UUID.fromString(systemId);
    }

    public CaseStatus resolvedState() {
        return state == null ? null : CaseStatus.valueOf(state);
    }

    public int pageNumber() {
        return page == null ? 0 : Integer.parseInt(page);
    }

    public int pageSize() {
        return size == null ? 20 : Integer.parseInt(size);
    }
}
