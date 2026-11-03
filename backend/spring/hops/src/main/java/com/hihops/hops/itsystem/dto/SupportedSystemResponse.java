package com.hihops.hops.itsystem.dto;

import java.util.List;
import java.util.UUID;

import com.hihops.hops.itsystem.domain.ItSystem;
import com.hihops.hops.itsystem.domain.SystemFeature;

/** 시스템 필터·티켓에 표시하는 공개 시스템 정보. PRIMARY는 별도 권한 조회 대상이다. */
public record SupportedSystemResponse(UUID id, SystemCode code, String name, List<FeatureResponse> features) {
    public static SupportedSystemResponse of(ItSystem system, List<SystemFeature> features) {
        return new SupportedSystemResponse(system.getId(), SystemCode.fromStored(system.getCode()), system.getName(),
                features.stream().map(feature -> new FeatureResponse(feature.getId(), feature.getName())).toList());
    }

    public record FeatureResponse(UUID id, String name) {
    }
}
