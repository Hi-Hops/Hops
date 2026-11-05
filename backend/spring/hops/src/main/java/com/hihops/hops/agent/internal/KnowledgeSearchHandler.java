package com.hihops.hops.agent.internal;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hihops.hops.agent.dto.AiRunRequest;
import com.hihops.hops.agent.internal.KnowledgeSearchRequest.Kind;
import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.knowledge.domain.AccessLevel;
import com.hihops.hops.knowledge.domain.DocumentType;
import com.hihops.hops.knowledge.service.KnowledgeEvidenceAssembler.IntegrityException;
import com.hihops.hops.knowledge.service.KnowledgeQueryTokens;
import com.hihops.hops.knowledge.service.KnowledgeSearchService;

import lombok.RequiredArgsConstructor;

/**
 * 실행(runId)의 접근 문맥에서 검색 범위를 확정하고 요청이 그 범위를 넘는지 확인한 뒤 검색한다.
 * 공개 범위·시스템은 실행 입력에서만 정해지며, 요청은 종류와 시스템을 좁힐 수 있을 뿐이다.
 * 범위를 넘는 요청은 조용히 줄이지 않고 DENIED로 돌려준다. 검색 실패는 빈 결과가 아니라 503이다.
 */
@Service
@RequiredArgsConstructor
class KnowledgeSearchHandler {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchHandler.class);
    private static final String CONTRACT = "hops-system-1";
    private static final Set<Kind> EMPLOYEE_KINDS = EnumSet.of(Kind.GUIDE, Kind.POLICY);

    private final InternalRunAccess runAccess;
    private final KnowledgeSearchService search;

    @Transactional(readOnly = true)
    KnowledgeSearchResponse search(UUID runId, KnowledgeSearchRequest request) {
        var access = runAccess.active(runId, request.inquiryVersion());
        AiRunRequest input = access.input();
        checkProblemRef(input, request.problemRef());

        Set<AccessLevel> levels = EnumSet.noneOf(AccessLevel.class);
        for (String scope : input.accessContext().allowedDocumentScopes()) {
            switch (scope) {
                case "EMPLOYEE" -> levels.add(AccessLevel.EMPLOYEE);
                case "SYSTEM_STAFF" -> levels.add(AccessLevel.STAFF);
                default -> throw new BusinessException(ErrorCode.ACCESS_DENIED);
            }
        }
        if (levels.isEmpty()) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        Set<Kind> allowedKinds = levels.contains(AccessLevel.STAFF) ? EnumSet.allOf(Kind.class) : EMPLOYEE_KINDS;
        Set<UUID> accessibleSystems = new LinkedHashSet<>(input.accessContext().systemIds());
        int topK = request.effectiveTopK();
        var applied = new KnowledgeSearchResponse.Applied(scopeNames(levels), allowedKinds.stream().map(Enum::name).toList(),
                List.copyOf(accessibleSystems), topK);

        Set<Kind> kinds = request.kinds() == null ? allowedKinds : EnumSet.copyOf(request.kinds());
        if (!allowedKinds.containsAll(kinds)) {
            return denied(runId, request, applied, "KIND_NOT_ALLOWED");
        }
        Set<UUID> systems = request.systemIds() == null ? accessibleSystems : new LinkedHashSet<>(request.systemIds());
        if (!accessibleSystems.containsAll(systems)) {
            return denied(runId, request, applied, "SYSTEM_NOT_ALLOWED");
        }
        List<String> tokens = KnowledgeQueryTokens.of(request.query());
        if (tokens.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        List<KnowledgeSearchService.Hit> hits;
        try {
            hits = search.search(new KnowledgeSearchService.Query(tokens, levels,
                    kinds.stream().map(KnowledgeSearchHandler::documentType).collect(java.util.stream.Collectors.toCollection(
                            () -> EnumSet.noneOf(DocumentType.class))),
                    systems, access.inquiry().getRequester().getId(), topK));
        } catch (IntegrityException | DataAccessException exception) {
            // 검색어·본문은 로그에 남기지 않는다. 실패를 빈 결과로 바꾸지 않고 503으로 알린다.
            log.warn("지식 검색 실패 runId={} cause={}", runId, exception.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.SEARCH_UNAVAILABLE);
        }
        var results = hits.stream().map(hit -> new KnowledgeSearchResponse.Result(hit.assembled().evidence(),
                hit.assembled().documentKey(), hit.assembled().content(), hit.assembled().contentDigest(),
                hit.assembled().digestBasis(), hit.score(), hit.assembled().caseIndexStatus())).toList();
        return new KnowledgeSearchResponse(CONTRACT, runId, request.problemRef(), results.isEmpty() ? "NO_RESULT" : "FOUND",
                null, applied, results);
    }

    private static KnowledgeSearchResponse denied(UUID runId, KnowledgeSearchRequest request,
            KnowledgeSearchResponse.Applied applied, String reason) {
        return new KnowledgeSearchResponse(CONTRACT, runId, request.problemRef(), "DENIED", reason, applied, List.of());
    }

    /** 기존 문제 UUID는 이 실행 입력의 문제여야 한다. 새 문제(new:)는 서버가 아직 모르므로 형식만 본다. */
    private static void checkProblemRef(AiRunRequest input, String problemRef) {
        if (problemRef.startsWith("new:")) {
            return;
        }
        boolean known = input.problems().stream().anyMatch(problem -> problem.id().toString().equals(problemRef));
        if (!known) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    private static List<String> scopeNames(Set<AccessLevel> levels) {
        return levels.stream().map(level -> level == AccessLevel.STAFF ? "SYSTEM_STAFF" : "EMPLOYEE").sorted().toList();
    }

    private static DocumentType documentType(Kind kind) {
        return switch (kind) {
            case GUIDE -> DocumentType.EMPLOYEE_GUIDE;
            case POLICY -> DocumentType.POLICY;
            case RUNBOOK -> DocumentType.STAFF_RUNBOOK;
            case CASE -> DocumentType.CASE;
        };
    }
}
