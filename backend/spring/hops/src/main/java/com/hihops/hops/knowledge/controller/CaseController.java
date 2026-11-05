package com.hihops.hops.knowledge.controller;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hihops.hops.global.dto.PageResponse;
import com.hihops.hops.knowledge.dto.CaseDetailRequest;
import com.hihops.hops.knowledge.dto.CaseDetailResponse;
import com.hihops.hops.knowledge.dto.CaseListRequest;
import com.hihops.hops.knowledge.dto.CaseSummaryResponse;
import com.hihops.hops.knowledge.service.CaseQueryService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 시스템 주담당자의 사례 검토 목록·상세. 수정·승인·색인은 별도 명령 API가 맡는다. */
@RestController
@RequestMapping("/cases")
@RequiredArgsConstructor
public class CaseController {
    private final CaseQueryService cases;

    @GetMapping
    public PageResponse<CaseSummaryResponse> list(@AuthenticationPrincipal Jwt jwt,
            @Valid @ModelAttribute CaseListRequest request) {
        return cases.list(jwt, request);
    }

    @GetMapping("/{caseId}")
    public CaseDetailResponse detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID caseId,
            @Valid @ModelAttribute CaseDetailRequest request) {
        return cases.detail(jwt, caseId, request);
    }
}
