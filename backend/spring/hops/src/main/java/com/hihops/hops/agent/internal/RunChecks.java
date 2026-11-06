package com.hihops.hops.agent.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.agent.domain.AgentTask;
import com.hihops.hops.agent.domain.RunStatus;
import com.hihops.hops.agent.dto.AiRunRequest;
import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.inquiry.domain.Inquiry;

/**
 * AI가 내부 API(티켓 생성, 지식 검색, 관계 조회)를 부를 때 runId의 실행 입력을 검증하는 규칙을 한 곳에 둔다.
 * 조회 방식(행 잠금 여부)은 호출하는 서비스가 정하고, 이 클래스는 읽어 온 값의 검사만 한다.
 */
final class RunChecks {
    static final String CONTRACT = "hops-system-1";

    private RunChecks() {
    }

    /**
     * 실행 입력이 이 runId의 상담 실행이고 문의자·목적·권한 세대가 지금도 유효한지 확인한다.
     *
     * @param policies 문의자의 현재 policyRevision 조회 결과(활성 문의자·부서 한 행이어야 한다)
     * @throws BusinessException ACCESS_DENIED
     */
    static void checkAccess(UUID runId, AgentRun run, Inquiry inquiry, AiRunRequest input, List<Integer> policies) {
        var member = inquiry.getRequester();
        if (run.getTask() != AgentTask.INQUIRY_ANALYSIS || !CONTRACT.equals(input.contractVersion())
                || !runId.equals(input.runId()) || !inquiry.getId().equals(input.inquiryId())
                || member.isDeleted() || member.getDepartment().isDeleted()
                || input.accessContext() == null || !member.getId().equals(input.accessContext().actorId())
                || policies.size() != 1 || policies.getFirst() != input.accessContext().policyRevision()
                || !"EMPLOYEE_HELP".equals(input.accessContext().purpose())) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
    }

    /**
     * 문의 버전이 같고 이 실행이 문의의 현재 실행이며 진행 중이고 기한 전인지 확인한다.
     *
     * @throws BusinessException INQUIRY_VERSION_CONFLICT 또는 INVALID_STATE
     */
    static void checkActive(AgentRun run, Inquiry inquiry, AiRunRequest input, int inquiryVersion, Clock clock) {
        if (inquiry.getVersion() != inquiryVersion || run.getInputVersion() != inquiryVersion
                || input.inquiryVersion() != inquiryVersion) {
            throw new BusinessException(ErrorCode.INQUIRY_VERSION_CONFLICT);
        }
        if (inquiry.getCurrentRun() == null || !inquiry.getCurrentRun().getId().equals(run.getId())
                || !List.of(RunStatus.QUEUED, RunStatus.RUNNING).contains(run.getRunStatus())
                || !clock.instant().isBefore(Instant.parse(input.expiresAt()))) {
            throw new BusinessException(ErrorCode.INVALID_STATE);
        }
    }
}
