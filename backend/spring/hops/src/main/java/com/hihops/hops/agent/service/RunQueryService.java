package com.hihops.hops.agent.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.agent.domain.AgentTask;
import com.hihops.hops.agent.dto.RunSummaryResponse;
import com.hihops.hops.agent.repository.AgentRunRepository;
import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.inquiry.domain.Inquiry;

import lombok.RequiredArgsConstructor;

/**
 * GET /runs/{runId}: 원 문의 소유자의 상담 실행 (WIRE_CONTRACT 12.2).
 *
 * <p>실패한 실행도 200 RunSummary다(12.8 끝). 남의 실행·삭제된 문의의 실행·Curator 실행·없는 실행은 모두 같은 404다.
 * Curator 실행(CURATION)은 담당자 업무라 공개 RunSummary로 보여 주지 않는다.
 */
@Service
@RequiredArgsConstructor
public class RunQueryService {

    private final AgentRunRepository agentRunRepository;
    private final RunSummaryReader runSummaryReader;

    @Transactional(readOnly = true)
    public RunSummaryResponse findMyRun(UUID requesterId, UUID runId) {
        AgentRun run = agentRunRepository.findById(runId)
                .filter(found -> found.getTask() == AgentTask.INQUIRY_ANALYSIS)
                .filter(found -> isMine(found.getInquiry(), requesterId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return runSummaryReader.read(run);
    }

    // 지연 로딩 참조의 ID는 프록시가 알고 있어 문의자 행을 읽지 않는다
    private static boolean isMine(Inquiry inquiry, UUID requesterId) {
        return !inquiry.isDeleted() && inquiry.getRequester().getId().equals(requesterId);
    }
}
