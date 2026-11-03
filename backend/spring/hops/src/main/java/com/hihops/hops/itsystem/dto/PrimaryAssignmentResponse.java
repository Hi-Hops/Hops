package com.hihops.hops.itsystem.dto;

import java.util.UUID;

import com.hihops.hops.user.dto.UserSummaryResponse;

/** PRIMARY 관계 ID를 만들지 않고 현재 시스템·주담당자 두 필드만 반환한다. */
public record PrimaryAssignmentResponse(UUID systemId, UserSummaryResponse primaryUser) {
}
