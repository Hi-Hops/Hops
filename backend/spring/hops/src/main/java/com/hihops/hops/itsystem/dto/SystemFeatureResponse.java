package com.hihops.hops.itsystem.dto;

import java.util.UUID;

import com.hihops.hops.itsystem.domain.SystemFeature;

/** 시스템 안의 기능 (specs/contracts/DATA_TYPES.md 신규 절 SystemFeature: id·name). 기능 코드·설명은 AI용이라 내보내지 않는다. */
public record SystemFeatureResponse(UUID id, String name) {

    public static SystemFeatureResponse from(SystemFeature feature) {
        return new SystemFeatureResponse(feature.getId(), feature.getName());
    }
}
