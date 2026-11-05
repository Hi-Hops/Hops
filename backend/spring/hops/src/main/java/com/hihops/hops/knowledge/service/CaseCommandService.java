package com.hihops.hops.knowledge.service;

import java.util.List;
import java.util.UUID;

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
import com.hihops.hops.knowledge.dto.CaseApproveCommand;
import com.hihops.hops.knowledge.dto.CaseEditCommand;
import com.hihops.hops.ticket.domain.TicketStatus;
import com.hihops.hops.ticket.service.TicketProgressReader;

import lombok.RequiredArgsConstructor;
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
