package com.hihops.hops.agent.internal;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hihops.hops.agent.service.AnalysisRunProperties;
import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.knowledge.service.KnowledgeEvidenceAssembler.IntegrityException;
import com.hihops.hops.knowledge.service.KnowledgeQueryTokens;
import com.hihops.hops.relation.service.RelationSearchService;

import lombok.RequiredArgsConstructor;

/**
 * 실행(runId)의 접근 문맥에서 관계 조회 범위를 확정하고 요청이 그 범위를 넘는지 확인한 뒤 조회한다.
 * 범위를 넘는 요청은 조용히 줄이지 않고 DENIED로 돌려주고, 조회 실패는 빈 결과가 아니라 503이다.
 */
@Service
@RequiredArgsConstructor
class RelationSearchHandler {
    private static final Logger log = LoggerFactory.getLogger(RelationSearchHandler.class);
    private static final String CONTRACT = "hops-system-1";

    private final InternalRunAccess runAccess;
    private final RelationSearchService search;
    private final AnalysisRunProperties runProperties;

    @Transactional(readOnly = true)
    RelationSearchResponse search(UUID runId, RelationSearchRequest request) {
        var access = runAccess.active(runId, request.inquiryVersion());
        access.requireKnownProblem(request.problemRef());
        RunScope scope = RunScope.of(access.input(), access.inquiry().getRequester().getId(), runProperties.internalJudgement().enabled());
        List<String> tokens = request.keywords() == null ? List.of() : KnowledgeQueryTokens.of(String.join(" ", request.keywords()));
        Set<UUID> featureIds = request.featureIds() == null ? Set.of() : new LinkedHashSet<>(request.featureIds());
        if (tokens.isEmpty() && featureIds.isEmpty()) {
            // 검색할 단어도 기능 후보도 없으면 무엇을 찾을지 알 수 없다. 빈 결과로 바꾸지 않는다.
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        int limit = request.effectiveLimit();
        var applied = new RelationSearchResponse.Applied(scope.scopeNames(), List.copyOf(scope.systemIds()), limit, scope.internalRead());
        if (scope.internalRead()) {
            log.info("내부 판단 읽기 관계 조회 runId={}", runId);
        }

        Set<UUID> systems = request.systemIds() == null ? scope.systemIds() : new LinkedHashSet<>(request.systemIds());
        if (!scope.systemIds().containsAll(systems)) {
            return denied(runId, request, applied, "SYSTEM_NOT_ALLOWED");
        }
        try {
            if (!featureIds.isEmpty()) {
                var featureSystems = search.featureSystems(featureIds);
                if (featureSystems.size() != featureIds.size() || !systems.containsAll(featureSystems.values())) {
                    return denied(runId, request, applied, "FEATURE_NOT_ALLOWED");
                }
            }
            var symptoms = search.search(new RelationSearchService.Query(tokens, featureIds, scope.levels(), scope.includesStaff(),
                    systems, scope.memberId(), scope.internalRead(), limit));
            return new RelationSearchResponse(CONTRACT, runId, request.problemRef(), symptoms.isEmpty() ? "NO_RESULT" : "FOUND",
                    null, applied, symptoms);
        } catch (IntegrityException | DataAccessException exception) {
            // 검색어·본문은 로그에 남기지 않는다. 실패를 빈 결과로 바꾸지 않고 503으로 알린다.
            log.warn("관계 조회 실패 runId={} cause={}", runId, exception.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.SEARCH_UNAVAILABLE);
        }
    }

    private static RelationSearchResponse denied(UUID runId, RelationSearchRequest request, RelationSearchResponse.Applied applied,
            String reason) {
        return new RelationSearchResponse(CONTRACT, runId, request.problemRef(), "DENIED", reason, applied, List.of());
    }
}
