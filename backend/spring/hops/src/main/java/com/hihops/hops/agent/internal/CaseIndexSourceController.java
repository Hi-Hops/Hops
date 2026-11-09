package com.hihops.hops.agent.internal;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hihops.hops.knowledge.service.CaseIndexSourceService;
import com.hihops.hops.knowledge.service.CaseIndexSourceService.CaseIndexSource;

import lombok.RequiredArgsConstructor;

/** AI→Spring 승인 사례 색인 원본 조회. 인증은 /internal/v2/** 서비스 토큰 chain이 맡는다. */
@RestController
@RequiredArgsConstructor
public class CaseIndexSourceController {
    private final CaseIndexSourceService sources;

    @GetMapping("/internal/v2/cases/{caseId}/index-source")
    public CaseIndexSource source(@PathVariable UUID caseId, @RequestParam int caseVersion) {
        return sources.find(caseId, caseVersion);
    }
}
