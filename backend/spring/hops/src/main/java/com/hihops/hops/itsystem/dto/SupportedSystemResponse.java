package com.hihops.hops.itsystem.dto;

import java.util.List;
import java.util.UUID;

import com.hihops.hops.itsystem.domain.ItSystem;

/** 공개 카탈로그와 티켓이 같은 시스템 DTO를 사용하며 저장 코드는 계약 enum으로 검증한다. */
public record SupportedSystemResponse(UUID id, SystemCode code, String name, List<SystemFeatureResponse> features) {
    public static SupportedSystemResponse of(ItSystem system, List<SystemFeatureResponse> features) {
        return new SupportedSystemResponse(system.getId(), SystemCode.fromStored(system.getCode()), system.getName(), List.copyOf(features));
    }
}
