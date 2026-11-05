package com.hihops.hops.agent.internal;

import java.util.UUID;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

/** AI→Spring 관계 조회. 인증은 /internal/v2/** 서비스 토큰 chain이 맡고, 본문은 알려진 필드만 받는다({@link StrictRequestParser}). */
@RestController
@RequestMapping("/internal/v2/runs/{runId}/relation-searches")
@RequiredArgsConstructor
public class RelationSearchController {
    private final RelationSearchHandler handler;
    private final StrictRequestParser parser;

    @PostMapping
    public RelationSearchResponse search(@PathVariable UUID runId, @RequestBody JsonNode body) {
        return handler.search(runId, parser.parse(body, RelationSearchRequest.class));
    }
}
