package com.hihops.hops.agent.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.agent.domain.AgentTask;
import com.hihops.hops.agent.domain.RunStatus;
import com.hihops.hops.agent.dto.AiRunRequest;
import com.hihops.hops.agent.repository.AgentRunRepository;
import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.inquiry.domain.Inquiry;
import com.hihops.hops.inquiry.repository.InquiryRepository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/**
 * 읽기 전용 내부 API가 runId로 실행을 확인하는 공통 검사. 접근 범위는 요청이 아니라 저장된 실행 입력에서 확정한다.
 * 티켓 생성 API({@link TicketCreationService})와 같은 기준(문의자·policyRevision·목적·현재 실행·버전·기한)을 쓴다.
 * 읽기 전용이라 행 잠금은 잡지 않는다. 호출 쪽 거래 안에서 불러야 한다.
 */
@Component
@RequiredArgsConstructor
class InternalRunAccess {
    private static final String CONTRACT = "hops-system-1";

    private final AgentRunRepository runs;
    private final InquiryRepository inquiries;
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final EntityManager entities;
    private final Clock clock;

    record RunAccess(AgentRun run, Inquiry inquiry, AiRunRequest input) {
        /** 기존 문제 UUID는 이 실행 입력의 문제여야 한다. 새 문제(new:)는 서버가 아직 모르므로 형식만 본다. */
        void requireKnownProblem(String problemRef) {
            if (problemRef.startsWith("new:")) {
                return;
            }
            if (input.problems().stream().noneMatch(problem -> problem.id().toString().equals(problemRef))) {
                throw new BusinessException(ErrorCode.INVALID_INPUT);
            }
        }
    }

    /** 실행이 접수된 현재 실행이고 진행 중이며 문의 버전이 같고 기한 전일 때의 접근 문맥. */
    RunAccess active(UUID runId, int inquiryVersion) {
        AgentRun run = runs.findById(runId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Inquiry inquiry = inquiries.findByIdAndDeletedAtIsNull(run.getInquiry().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        // 취소·교체된 실행의 캐시를 쓰지 않는다.
        entities.refresh(run);
        AiRunRequest input = mapper.readValue(run.getInputSnapshot(), AiRunRequest.class);
        var member = inquiry.getRequester();
        List<Integer> policies = jdbc.queryForList("""
                SELECT m.policy_revision FROM member m JOIN department d ON d.id=m.department_id
                WHERE m.id=? AND m.deleted_at IS NULL AND d.deleted_at IS NULL
                """, Integer.class, member.getId());
        if (run.getTask() != AgentTask.INQUIRY_ANALYSIS || !CONTRACT.equals(input.contractVersion())
                || !runId.equals(input.runId()) || !inquiry.getId().equals(input.inquiryId())
                || member.isDeleted() || member.getDepartment().isDeleted()
                || input.accessContext() == null || !member.getId().equals(input.accessContext().actorId())
                || policies.size() != 1 || policies.getFirst() != input.accessContext().policyRevision()
                || !"EMPLOYEE_HELP".equals(input.accessContext().purpose())) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        if (inquiry.getVersion() != inquiryVersion || run.getInputVersion() != inquiryVersion
                || input.inquiryVersion() != inquiryVersion) {
            throw new BusinessException(ErrorCode.INQUIRY_VERSION_CONFLICT);
        }
        if (inquiry.getCurrentRun() == null || !inquiry.getCurrentRun().getId().equals(run.getId())
                || !List.of(RunStatus.QUEUED, RunStatus.RUNNING).contains(run.getRunStatus())
                || !clock.instant().isBefore(Instant.parse(input.expiresAt()))) {
            throw new BusinessException(ErrorCode.INVALID_STATE);
        }
        return new RunAccess(run, inquiry, input);
    }
}
