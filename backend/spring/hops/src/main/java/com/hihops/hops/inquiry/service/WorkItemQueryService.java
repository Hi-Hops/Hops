package com.hihops.hops.inquiry.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.inquiry.dto.ProblemResponse.CreationResultResponse;
import com.hihops.hops.inquiry.repository.InquiryProblemRepository;

import lombok.RequiredArgsConstructor;

/**
 * 티켓 생성 작업 결과 조회 (WIRE_CONTRACT 12.2 GET /work-items/{workItemId}, 원 문의 소유자). workItemId = problemId.
 *
 * <p>값은 문의 상세의 creationResult와 같은 {@link ProblemProjector#creation}이다. 없는 문제·삭제된 문제·삭제된 문의·
 * 다른 사람의 문의·작업이 없는 문제는 모두 404로 같게 답해 존재 여부를 드러내지 않는다.
 */
@Service
@RequiredArgsConstructor
public class WorkItemQueryService {
    private final InquiryProblemRepository problems;
    private final ProblemProjector projector;

    @Transactional(readOnly = true)
    public CreationResultResponse find(UUID requesterId, UUID workItemId) {
        var problem = problems.findById(workItemId).filter(found -> !found.isDeleted())
                .filter(found -> !found.getInquiry().isDeleted()
                        && found.getInquiry().getRequester().getId().equals(requesterId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        var result = projector.creation(problem);
        if (result == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        return result;
    }
}
