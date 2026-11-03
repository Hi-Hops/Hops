package com.hihops.hops.itsystem.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hihops.hops.itsystem.dto.SupportedSystemResponse;
import com.hihops.hops.itsystem.service.SystemQueryService;

import lombok.RequiredArgsConstructor;

/** 문의할 수 있는 시스템 목록 API (WIRE_CONTRACT 12.2). 로그인한 사람이면 누구나 조회한다. */
@RestController
@RequestMapping("/systems") // 실제 주소는 /api/v2/systems (ApiPathConfig)
@RequiredArgsConstructor
public class SystemController {

    private final SystemQueryService systemQueryService;

    @GetMapping
    public List<SupportedSystemResponse> systems() {
        return systemQueryService.findSupportedSystems();
    }
}
