package com.hihops.hops.itsystem.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hihops.hops.itsystem.dto.SupportedSystemResponse;
import com.hihops.hops.itsystem.dto.SystemFeatureResponse;
import com.hihops.hops.itsystem.repository.ItSystemRepository;
import com.hihops.hops.itsystem.repository.SystemFeatureRepository;

import lombok.RequiredArgsConstructor;

/** 문의할 수 있는 시스템 목록 조회 (WIRE_CONTRACT 12.2 GET /systems). */
@Service
@RequiredArgsConstructor
public class SystemQueryService {

    private final ItSystemRepository itSystemRepository;
    private final SystemFeatureRepository systemFeatureRepository;

    /** 삭제되지 않은 시스템과 그 기능. 시스템·기능 모두 코드 순이고, 기능이 없는 시스템은 features=[]다. */
    @Transactional(readOnly = true)
    public List<SupportedSystemResponse> findSupportedSystems() {
        // 시스템은 3개뿐이라 전체를 읽고 삭제된 것만 뺀다. 기능은 한 번에 읽어 시스템별로 나눈다(시스템마다 따로 조회하지 않게).
        Map<UUID, List<SystemFeatureResponse>> featuresBySystem = systemFeatureRepository.findAllActive().stream()
                .collect(Collectors.groupingBy(feature -> feature.getItSystem().getId(),
                        Collectors.mapping(SystemFeatureResponse::from, Collectors.toList())));
        return itSystemRepository.findAll(Sort.by("code")).stream()
                .filter(system -> !system.isDeleted())
                .map(system -> SupportedSystemResponse.of(system, featuresBySystem.getOrDefault(system.getId(), List.of())))
                .toList();
    }
}
