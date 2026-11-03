package com.hihops.hops.itsystem.dto;

import java.util.List;
import java.util.UUID;

import com.hihops.hops.itsystem.domain.ItSystem;

/**
 * 문의할 수 있는 시스템 (specs/contracts/DATA_TYPES.md 신규 절 SupportedSystem: id·code·name·features).
 *
 * <p>직원에게 보이는 목록이라 주담당자 같은 내부 담당 정보는 넣지 않는다 (WIRE_CONTRACT 12.2).
 * 티켓 응답의 reportedSystem·responsibleSystem도 같은 형식이다.
 */
public record SupportedSystemResponse(UUID id, String code, String name, List<SystemFeatureResponse> features) {

    public static SupportedSystemResponse of(ItSystem system, List<SystemFeatureResponse> features) {
        return new SupportedSystemResponse(system.getId(), system.getCode(), system.getName(), List.copyOf(features));
    }
}
