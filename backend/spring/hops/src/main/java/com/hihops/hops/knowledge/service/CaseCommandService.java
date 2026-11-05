package com.hihops.hops.knowledge.service;

import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import com.hihops.hops.auth.service.AuthService;
import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.global.idempotency.CommandResult;
import com.hihops.hops.global.idempotency.IdempotencyService;
import com.hihops.hops.global.idempotency.IdempotentRequest;
import com.hihops.hops.global.idempotency.ResourceRef;
import com.hihops.hops.global.idempotency.ResourceType;
import com.hihops.hops.knowledge.domain.AccessLevel;
import com.hihops.hops.knowledge.domain.CaseStatus;
import com.hihops.hops.knowledge.domain.ResolvedCase;
import com.hihops.hops.knowledge.domain.IndexStatus;
import com.hihops.hops.knowledge.domain.IndexStore;
import com.hihops.hops.knowledge.dto.CaseApproveCommand;
import com.hihops.hops.knowledge.dto.IndexCheckCommand;
import com.hihops.hops.knowledge.dto.IndexRetryCommand;
import com.hihops.hops.knowledge.repository.KnowledgeDocumentVersionRepository;
import com.hihops.hops.knowledge.dto.CaseEditCommand;
import com.hihops.hops.ticket.domain.TicketStatus;
import com.hihops.hops.ticket.service.TicketProgressReader;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 사례 변경 명령 (WIRE12 POST /cases/{caseId}/versions). 수정·승인·색인은 각각 이 서비스에 명령을 더한다.
 *
 * <p>MVP는 현재 DRAFT만 고치고 같은 사례의 version_no를 올린다(F011-CONTRACT-04). 과거 버전 행은 따로 남기지 않는다.
 * 승인된 사례의 내용·반영 상태는 바꾸지 않는다.
 */
@Service
@RequiredArgsConstructor
public class CaseCommandService {
    private final CaseCommandAccess access;
    private final IdempotencyService idempotency;
    private final JsonMapper mapper;
    private final AuthService auth;
    private final TicketProgressReader progress;
    private final CaseApprovalWriter approvals;
    private final KnowledgeDocumentVersionRepository documentVersions;
    private final ApplicationEventPublisher events;

    public CommandResult edit(Jwt jwt, IdempotentRequest request, UUID caseId, CaseEditCommand command) {
        access.check(jwt, caseId);
        return idempotency.execute(request, operationId -> saveEdit(jwt, caseId, command));
    }

    /**
     * 사례 승인 (WIRE12 POST /cases/{caseId}/approvals). 승인·검색 문서·반영 요청을 한 거래로 저장한다.
     * 순서는 수정과 같다: 버전 충돌 → 승인 상태 → 승인 전제(문의자 해결 확인).
     */
    public CommandResult approve(Jwt jwt, IdempotentRequest request, UUID caseId, CaseApproveCommand command) {
        access.check(jwt, caseId);
        return idempotency.execute(request, operationId -> saveApproval(jwt, caseId, command));
    }

    private ResourceRef saveApproval(Jwt jwt, UUID caseId, CaseApproveCommand command) {
        ResolvedCase resolved = access.lock(jwt, caseId);
        if (resolved.getVersionNo() != command.expectedCaseVersion()) {
            throw new BusinessException(ErrorCode.CASE_VERSION_CONFLICT);
        }
        if (resolved.getCaseStatus() != CaseStatus.DRAFT) {
            throw new BusinessException(ErrorCode.INVALID_STATE);
        }
        var confirmation = progress.resolution(resolved.getSourceWorkLog());
        if (!"RESOLVED".equals(confirmation.state())
                || resolved.getSourceWorkLog().getTicket().getTicketStatus() != TicketStatus.RESOLVED) {
            throw new BusinessException(ErrorCode.APPROVAL_PRECONDITION_FAILED);
        }
        approvals.approve(resolved, auth.currentMember(jwt), confirmation);
        return new ResourceRef(ResourceType.CASE, caseId);
    }

    /**
     * 색인 확인 (WIRE12 POST /cases/{caseId}/index-checks). 이 거래는 검사만 하고, 원격 조회·재접수는 commit 뒤에 한다.
     * 순서: 버전 충돌 → 승인 여부 → 확인 대상 상태(PENDING·RUNNING·UNKNOWN).
     */
    public CommandResult checkIndex(Jwt jwt, IdempotentRequest request, UUID caseId, IndexCheckCommand command) {
        access.check(jwt, caseId);
        return idempotency.execute(request, operationId -> {
            ResolvedCase resolved = lockApproved(jwt, caseId, command.expectedCaseVersion());
            if (!resolved.indexStatus(command.store()).isOpen()) {
                throw new BusinessException(ErrorCode.INVALID_STATE);
            }
            JsonNode stored = storedRequest(resolved, command.store());
            events.publishEvent(new CaseIndexRequested(caseId, command.store(), stored.path("jobId").asString(),
                    stored.path("attempt").asInt(), CaseIndexRequested.Mode.CHECK));
            return new ResourceRef(ResourceType.CASE, caseId);
        });
    }

    /**
     * 색인 재시도 (WIRE12 POST /cases/{caseId}/index-retries). FAILED 저장소만, 같은 jobId의 다음 attempt 요청과 PENDING을
     * 먼저 저장한 뒤 commit 뒤에 전달한다. 승인 본문·digest와 성공한 저장소는 바꾸지 않는다.
     */
    public CommandResult retryIndex(Jwt jwt, IdempotentRequest request, UUID caseId, IndexRetryCommand command) {
        access.check(jwt, caseId);
        return idempotency.execute(request, operationId -> {
            ResolvedCase resolved = lockApproved(jwt, caseId, command.expectedCaseVersion());
            if (resolved.indexStatus(command.store()) != IndexStatus.FAILED) {
                throw new BusinessException(ErrorCode.INDEX_NOT_RETRYABLE);
            }
            JsonNode stored = storedRequest(resolved, command.store());
            var version = documentVersions.findCaseVersion(caseId.toString(), resolved.getVersionNo())
                    .orElseThrow(() -> new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));
            ObjectNode metadata = (ObjectNode) mapper.readTree(version.getSourceMetadata());
            ObjectNode next = ((ObjectNode) stored).deepCopy();
            int attempt = stored.path("attempt").asInt() + 1;
            next.put("attempt", attempt);
            ((ObjectNode) metadata.path("indexRequests")).set(command.store().name(), next);
            version.replaceSourceMetadata(mapper.writeValueAsString(metadata));
            resolved.updateIndexStatus(command.store(), IndexStatus.PENDING);
            events.publishEvent(new CaseIndexRequested(caseId, command.store(), next.path("jobId").asString(), attempt,
                    CaseIndexRequested.Mode.DELIVER));
            return new ResourceRef(ResourceType.CASE, caseId);
        });
    }

    private ResolvedCase lockApproved(Jwt jwt, UUID caseId, int expectedCaseVersion) {
        ResolvedCase resolved = access.lock(jwt, caseId);
        if (resolved.getVersionNo() != expectedCaseVersion) {
            throw new BusinessException(ErrorCode.CASE_VERSION_CONFLICT);
        }
        if (resolved.getCaseStatus() != CaseStatus.APPROVED) {
            throw new BusinessException(ErrorCode.INVALID_STATE);
        }
        return resolved;
    }

    /** 승인 거래가 저장한 저장소별 현재 요청. 없으면 저장 형식 오류이므로 새로 만들지 않고 503이다. */
    private JsonNode storedRequest(ResolvedCase resolved, IndexStore store) {
        JsonNode request = documentVersions.findCaseVersion(resolved.getId().toString(), resolved.getVersionNo())
                .map(version -> mapper.readTree(version.getSourceMetadata()).path("indexRequests").path(store.name()))
                .orElse(null);
        if (request == null || !request.isObject() || !request.path("jobId").isString() || !request.path("attempt").isInt()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        return request;
    }

    private ResourceRef saveEdit(Jwt jwt, UUID caseId, CaseEditCommand command) {
        ResolvedCase resolved = access.lock(jwt, caseId);
        if (resolved.getVersionNo() != command.expectedCaseVersion()) {
            throw new BusinessException(ErrorCode.CASE_VERSION_CONFLICT);
        }
        if (resolved.getCaseStatus() != CaseStatus.DRAFT) {
            throw new BusinessException(ErrorCode.INVALID_STATE);
        }
        resolved.editDraft(command.title(), command.problemStatement(), command.action(), command.verification(),
                command.applicability(), command.appliesToSystemVersion(), accessLevel(command.accessScope()),
                withLimitations(resolved.getConfirmedFacts(), command.limitationList()));
        return new ResourceRef(ResourceType.CASE, caseId);
    }

    /** Curator가 보존한 facts·sourceMessageIds·knowledge는 그대로 두고 제한 조건만 바꾼다. 저장 형식을 알 수 없으면 503이다. */
    private String withLimitations(String stored, List<String> limitations) {
        if (!(mapper.readTree(stored) instanceof ObjectNode facts)) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        facts.set("limitations", mapper.valueToTree(limitations));
        return mapper.writeValueAsString(facts);
    }

    private static AccessLevel accessLevel(CaseEditCommand.AccessScope scope) {
        return switch (scope) {
            case EMPLOYEE -> AccessLevel.EMPLOYEE;
            case SYSTEM_STAFF -> AccessLevel.STAFF;
        };
    }
}
