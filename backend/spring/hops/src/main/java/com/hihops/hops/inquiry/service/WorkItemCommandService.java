package com.hihops.hops.inquiry.service;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.hihops.hops.agent.internal.TicketCreationService;
import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.global.idempotency.CommandResult;
import com.hihops.hops.global.idempotency.IdempotencyService;
import com.hihops.hops.global.idempotency.IdempotentRequest;
import com.hihops.hops.global.idempotency.ResourceRef;
import com.hihops.hops.global.idempotency.ResourceType;
import com.hihops.hops.inquiry.domain.CreationOutcome;
import com.hihops.hops.inquiry.domain.InquiryStatus;
import com.hihops.hops.inquiry.domain.ProblemStatus;
import com.hihops.hops.inquiry.dto.WorkItemRetryCommand;
import com.hihops.hops.inquiry.repository.InquiryProblemRepository;
import com.hihops.hops.inquiry.repository.InquiryRepository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * 티켓 생성 작업 재시도 (WIRE_CONTRACT 12.2 POST /work-items/{workItemId}/retries, 원 문의 소유자). workItemId = problemId.
 *
 * <p>NOT_CREATED 작업만 저장해 둔 AI 요청으로 같은 workItemId에 티켓 생성을 다시 시도한다. 응답은 200 CommandReceipt·
 * resource=WORK_ITEM(같은 작업)이며 프론트는 GET /work-items로 결과를 읽는다. 책임자가 여전히 없으면 접수는 되지만 작업은
 * NOT_CREATED로 남는다. 같은 요청 키는 처음 결과를 돌려준다.
 *
 * <p>거절: 남의 작업·없는 작업·삭제된 문의는 404, 보낸 문의 버전이나 작업의 입력 버전이 현재 문의 버전과 다르면 409
 * INQUIRY_VERSION_CONFLICT, 저장 여부 미확인(UNKNOWN)·진행 중(PENDING)·삭제된 티켓 행이 남은 작업·끝난 문의·저장된 요청이 없는
 * 작업은 409 WORK_ITEM_NOT_RETRYABLE. 이미 STORED면 기존 참조를 그대로 돌려준다. 같은 문의의 변경은 문의 행 잠금으로 하나씩
 * 처리하므로 동시에 와도 티켓은 하나다.
 */
@Service
@RequiredArgsConstructor
public class WorkItemCommandService {
    private final IdempotencyService idempotency;
    private final InquiryProblemRepository problems;
    private final InquiryRepository inquiries;
    private final ProblemProjector projector;
    private final TicketCreationService creation;
    private final EntityManager entities;

    /** 트랜잭션 밖(Controller)에서 부른다. */
    public CommandResult retry(IdempotentRequest request, UUID workItemId, WorkItemRetryCommand command) {
        // 같은 키 재확인도 현재 권한 검사 뒤에 한다 (WIRE_CONTRACT 12.1)
        problems.findMine(workItemId, request.actorId()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return idempotency.execute(request,
                operationId -> new ResourceRef(ResourceType.WORK_ITEM, retryWork(request.actorId(), workItemId, command)));
    }

    private UUID retryWork(UUID requesterId, UUID workItemId, WorkItemRetryCommand command) {
        var found = problems.findMine(workItemId, requesterId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        var inquiry = inquiries.findByIdAndDeletedAtIsNull(found.getInquiry().getId())
                .filter(locked -> locked.getRequester().getId().equals(requesterId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (inquiry.getVersion() != command.expectedInquiryVersion()) {
            throw new BusinessException(ErrorCode.INQUIRY_VERSION_CONFLICT);
        }
        // 잠금을 기다리는 사이 다른 요청이 저장했을 수 있다. 잠금 전에 읽은 엔티티는 영속성 컨텍스트에 남아 있으므로 다시 읽는다.
        entities.refresh(found);
        var problem = found;
        var current = projector.creation(problem);
        if (current == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (CreationOutcome.STORED.name().equals(current.outcome())) {
            return workItemId;
        }
        // 문제가 티켓 생성 실패 상태가 아니면(범위 밖·해결됨·안내됨) 다시 만들지 않는다. AI 내부 생성과 같은 기준이다.
        boolean failed = problem.getProblemStatus() == ProblemStatus.TICKET_FAILED;
        boolean open = inquiry.getInquiryStatus() != InquiryStatus.RESOLVED && inquiry.getInquiryStatus() != InquiryStatus.CLOSED;
        if (!CreationOutcome.NOT_CREATED.name().equals(current.outcome()) || !open || !failed) {
            throw new BusinessException(ErrorCode.WORK_ITEM_NOT_RETRYABLE);
        }
        if (current.inquiryVersion() != inquiry.getVersion()) {
            throw new BusinessException(ErrorCode.INQUIRY_VERSION_CONFLICT);
        }
        creation.retryByRequester(workItemId, inquiry);
        return workItemId;
    }
}
