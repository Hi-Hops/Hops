package com.hihops.hops.inquiry.controller;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hihops.hops.inquiry.dto.ProblemResponse.CreationResultResponse;
import com.hihops.hops.inquiry.service.WorkItemQueryService;

import lombok.RequiredArgsConstructor;

/** 티켓 생성 작업 조회 (WIRE_CONTRACT 12.2). 실제 주소는 /api/v2/work-items (ApiPathConfig). */
@RestController
@RequestMapping("/work-items")
@RequiredArgsConstructor
public class WorkItemController {
    private final WorkItemQueryService workItems;

    @GetMapping("/{workItemId}")
    public CreationResultResponse workItem(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workItemId) {
        return workItems.find(UUID.fromString(jwt.getSubject()), workItemId);
    }
}
