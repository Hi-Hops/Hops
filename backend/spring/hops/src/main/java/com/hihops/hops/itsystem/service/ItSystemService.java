package com.hihops.hops.itsystem.service;

import java.util.Collection;
import java.util.function.Function;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hihops.hops.auth.service.AuthService;
import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.itsystem.domain.ItSystem;
import com.hihops.hops.itsystem.domain.SystemFeature;
import com.hihops.hops.itsystem.dto.PrimaryAssignmentResponse;
import com.hihops.hops.itsystem.dto.SupportedSystemResponse;
import com.hihops.hops.itsystem.dto.SystemFeatureResponse;
import com.hihops.hops.itsystem.repository.ItSystemRepository;
import com.hihops.hops.itsystem.repository.SystemFeatureRepository;
import com.hihops.hops.user.domain.Role;
import com.hihops.hops.user.dto.UserSummaryResponse;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ItSystemService {
    private final AuthService authService;
    private final ItSystemRepository systems;
    private final SystemFeatureRepository features;

    @Transactional(readOnly = true)
    public PrimaryAssignmentResponse primary(Jwt jwt, UUID systemId) {
        var member = authService.currentMember(jwt);
        systems.findByIdAndDeletedAtIsNull(systemId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        UUID owner = systems.findActivePrimaryOwnerId(systemId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));
        if (!owner.equals(member.getId()) && !member.getContractRoles().contains(Role.BIZ_ADMIN)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        return new PrimaryAssignmentResponse(systemId, UserSummaryResponse.from(authService.currentMember(owner)));
    }

    /** 목록 안의 시스템 기능을 한 번에 읽는다. 티켓 Service의 읽기 트랜잭션 안에서도 사용한다. */
    @Transactional(readOnly = true)
    public Map<UUID, SupportedSystemResponse> responses(Collection<ItSystem> values) {
        Map<UUID, ItSystem> unique = values.stream().collect(Collectors.toMap(ItSystem::getId,
                Function.identity(), (first, second) -> first));
        if (unique.isEmpty()) return Map.of();
        Map<UUID, List<SystemFeature>> bySystem = features.findActiveBySystemIds(unique.keySet()).stream()
                .collect(Collectors.groupingBy(feature -> feature.getItSystem().getId()));
        return unique.values().stream().collect(Collectors.toMap(ItSystem::getId,
                system -> SupportedSystemResponse.of(system, bySystem.getOrDefault(system.getId(), List.of()).stream().map(SystemFeatureResponse::from).toList())));
    }
}
