package com.hihops.hops.knowledge.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hihops.hops.auth.service.AuthService;
import com.hihops.hops.global.dto.PageResponse;
import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.global.exception.ErrorResponse;
import com.hihops.hops.global.filter.RequestIdFilter;
import com.hihops.hops.itsystem.repository.ItSystemQueryRepository;
import com.hihops.hops.knowledge.domain.CaseStatus;
import com.hihops.hops.knowledge.domain.IndexStatus;
import com.hihops.hops.knowledge.domain.IndexStore;
import com.hihops.hops.knowledge.domain.ResolvedCase;
import com.hihops.hops.knowledge.dto.CaseApprovalResponse;
import com.hihops.hops.knowledge.dto.CaseDetailRequest;
import com.hihops.hops.knowledge.dto.CaseDetailResponse;
import com.hihops.hops.knowledge.dto.CaseListRequest;
import com.hihops.hops.knowledge.dto.CaseSummaryResponse;
import com.hihops.hops.knowledge.dto.CaseVersionResponse;
import com.hihops.hops.knowledge.dto.IndexResultResponse;
import com.hihops.hops.knowledge.dto.PublicationResponse;
import com.hihops.hops.knowledge.repository.KnowledgeDocumentVersionRepository;
import com.hihops.hops.knowledge.repository.ResolvedCaseRepository;
import com.hihops.hops.ticket.dto.TicketScope;
import com.hihops.hops.ticket.service.TicketAuthorization;
import com.hihops.hops.ticket.service.TicketProgressReader;
import com.hihops.hops.ticket.service.TicketStoredData;
import com.hihops.hops.user.domain.Member;
import com.hihops.hops.user.dto.UserSummaryResponse;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

/**
 * 사례 검토 목록·상세 조회 (WIRE12 GET /cases, GET /cases/{caseId}).
 *
 * <p>resolved_case 현재 행만 투영한다. 저장 형식은 Curator 결과 처리(CurationResultHandler)가 정한다:
 * confirmed_facts = {facts, limitations, sourceMessageIds, knowledge}, relation_candidates = entityLinks (DATA.md CaseVersion 투영).
 * 원 처리 기록의 해결 확인 요청은 ticket_event에 저장된 값을 읽는다. GET에서 실행·색인·상태 변경을 만들지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CaseQueryService {
    private final AuthService auth;
    private final TicketAuthorization authorization;
    private final ItSystemQueryRepository systems;
    private final ResolvedCaseRepository cases;
    private final KnowledgeDocumentVersionRepository documentVersions;
    private final TicketProgressReader progress;
    private final TicketStoredData data;

    /** 현재 사용자가 주담당자인 시스템의 사례만. 다른 시스템 지정과 담당 시스템 없음은 티켓 목록과 같이 403이다. */
    public PageResponse<CaseSummaryResponse> list(Jwt jwt, CaseListRequest request) {
        Member member = auth.currentMember(jwt);
        var allowed = authorization.allowedSystemIds(member, TicketScope.ASSIGNED_SYSTEMS, request.resolvedSystemId());
        var page = cases.findVisible(allowed, request.resolvedState(),
                PageRequest.of(request.pageNumber(), request.pageSize()));
        return new PageResponse<>(page.stream().map(this::summary).toList(), request.pageNumber(), request.pageSize(),
                page.getTotalElements());
    }

    /** 현재 사례 시스템의 주담당자만 본다. 권한 없음·과거 버전은 존재 여부를 드러내지 않도록 404다(SEC-03). */
    public CaseDetailResponse detail(Jwt jwt, UUID caseId, CaseDetailRequest request) {
        Member member = auth.currentMember(jwt);
        ResolvedCase resolved = cases.findActiveDetail(caseId).orElseThrow(CaseQueryService::notFound);
        if (systems.findActivePrimaryOwnerId(resolved.getItSystem().getId()).filter(member.getId()::equals).isEmpty()) {
            throw notFound();
        }
        Integer requested = request.requestedVersion();
        if (requested != null && requested != resolved.getVersionNo()) throw notFound();
        CaseApprovalResponse approval = null;
        if (resolved.getCaseStatus() == CaseStatus.APPROVED) {
            if (resolved.getApprovedBy() == null || resolved.getApprovedAt() == null) throw unavailable();
            approval = new CaseApprovalResponse(resolved.getId(), resolved.getVersionNo(),
                    UserSummaryResponse.from(resolved.getApprovedBy()), resolved.getApprovedAt());
        }
        boolean searchable = searchable(resolved);
        return new CaseDetailResponse(version(resolved), approval, indexes(resolved),
                new PublicationResponse(resolved.getId(), resolved.getVersionNo(), searchable ? "SEARCHABLE" : "PRIVATE",
                        searchable));
    }

    /**
     * 승인 거래가 source_metadata.indexRequests에 저장한 저장소별 요청을 읽어 반영 시도로 보여 준다.
     * 상태는 resolved_case의 두 상태 컬럼이 정본이다. 초안이거나 저장한 요청이 없으면 빈 목록이다.
     */
    private List<IndexResultResponse> indexes(ResolvedCase resolved) {
        if (resolved.getCaseStatus() != CaseStatus.APPROVED) return List.of();
        var stored = documentVersions.findCaseVersion(resolved.getId().toString(), resolved.getVersionNo());
        if (stored.isEmpty()) return List.of();
        JsonNode requests = data.parse(stored.get().getSourceMetadata()).path("indexRequests");
        if (!requests.isObject()) throw unavailable();
        List<IndexResultResponse> result = new ArrayList<>();
        for (String store : CaseApprovalWriter.STORES) {
            JsonNode request = requests.path(store);
            if (!request.isObject() || !resolved.getId().toString().equals(request.path("caseId").asString(""))
                    || request.path("caseVersion").asInt(-1) != resolved.getVersionNo()
                    || !request.path("attempt").isInt() || !request.path("jobId").isString()) {
                throw unavailable();
            }
            IndexStatus state = resolved.indexStatus(IndexStore.valueOf(store));
            // 실패 원인은 저장하지 않는다. 원격 오류 세부를 노출하지 않고 일반 오류로 알린다.
            ErrorResponse error = state == IndexStatus.FAILED
                    ? ErrorResponse.of(ErrorCode.SERVICE_UNAVAILABLE, RequestIdFilter.currentRequestId()) : null;
            result.add(new IndexResultResponse(UUID.fromString(request.path("jobId").asString()), resolved.getId(),
                    resolved.getVersionNo(), store, state.name(), request.path("attempt").asInt(), error,
                    resolved.getUpdatedAt()));
        }
        return List.copyOf(result);
    }

    private CaseSummaryResponse summary(ResolvedCase resolved) {
        if (resolved.getCuratorRun() == null) throw unavailable();
        var generation = progress.generation(resolved.getCuratorRun().getId());
        if (!resolved.getId().equals(generation.caseId())) throw unavailable();
        return new CaseSummaryResponse(resolved.getId(), resolved.getTitle(), resolved.getItSystem().getId(),
                resolved.getVersionNo(), resolved.getCaseStatus().name(),
                searchable(resolved) ? List.of(resolved.getVersionNo()) : List.of(), generation.state(),
                resolved.getUpdatedAt());
    }

    private CaseVersionResponse version(ResolvedCase resolved) {
        var work = resolved.getSourceWorkLog();
        var ticket = work.getTicket();
        if (resolved.getApplicability() == null || resolved.getApplicability().isBlank()) throw unavailable();
        var confirmation = progress.resolution(work);
        JsonNode facts = data.parse(resolved.getConfirmedFacts());
        if (!facts.isObject()) throw unavailable();
        return new CaseVersionResponse(resolved.getId(), resolved.getVersionNo(), resolved.getCaseStatus().name(),
                ticket.getId(), ticket.getInquiryProblem().getId(), work.getId(), confirmation.id(),
                resolved.getItSystem().getId(), resolved.getCaseType().name(), resolved.getProblemStatement(),
                resolved.getConfirmedCause(), resolved.getTitle(), resolved.getActionDetail(),
                resolved.getVerificationResult(), resolved.getApplicability(), limitations(facts.path("limitations")),
                resolved.getAppliedVersion(), accessScope(resolved), origin(resolved),
                confirmation.confirmationOrigin(), data.list(facts.path("sourceMessageIds"), UUID.class),
                resolved.getCreatedAt());
    }

    /** 승인됐고 같은 버전의 Vector·Neo4j 반영이 모두 성공한 경우만 공개다. 조회자는 이미 해당 시스템 주담당자로 확인했다. */
    private static boolean searchable(ResolvedCase resolved) {
        return resolved.getCaseStatus() == CaseStatus.APPROVED && resolved.getVectorStatus() == IndexStatus.SUCCEEDED
                && resolved.getGraphStatus() == IndexStatus.SUCCEEDED;
    }

    /** Curator가 보존한 제한 조건 목록. 문자열이 아닌 값이 섞였으면 빈 목록으로 바꾸지 않고 503이다. */
    private static List<String> limitations(JsonNode node) {
        if (!node.isArray()) throw unavailable();
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isString()) throw unavailable();
            result.add(item.asString());
        }
        return List.copyOf(result);
    }

    static String accessScope(ResolvedCase resolved) {
        return switch (resolved.getAccessLevel()) {
            case EMPLOYEE -> "EMPLOYEE";
            case STAFF -> "SYSTEM_STAFF";
        };
    }

    static String origin(ResolvedCase resolved) {
        return switch (resolved.getOriginType()) {
            case SYNTHETIC -> "SYNTHETIC";
            case DEMO_VERIFIED -> "DEMO_VERIFIED";
            case PRODUCTION -> "OPERATIONAL";
        };
    }

    private static BusinessException notFound() {
        return new BusinessException(ErrorCode.NOT_FOUND);
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
    }
}
