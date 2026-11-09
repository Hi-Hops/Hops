package com.hihops.hops.agent.internal;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.hihops.hops.agent.domain.AgentRun;
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

        Set<UUID> systemsFor(String problemRef, Set<UUID> allowed) {
            return input.problems().stream()
                    .filter(problem -> problem.id().toString().equals(problemRef) && problem.reportedSystemId() != null)
                    .findFirst()
                    .map(problem -> allowed.contains(problem.reportedSystemId()) ? Set.of(problem.reportedSystemId())
                            : Set.<UUID>of())
                    .orElse(allowed);
        }
    }

    /** 실행이 접수된 현재 실행이고 진행 중이며 문의 버전이 같고 기한 전일 때의 접근 문맥. */
    RunAccess active(UUID runId, int inquiryVersion) {
        AgentRun run = runs.findById(runId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        // 읽기 전용 API라 문의 행을 잠그지 않는다(findByIdAndDeletedAtIsNull은 쓰기 잠금이라 읽기 전용 거래에서 실패한다).
        Inquiry inquiry = inquiries.findById(run.getInquiry().getId()).filter(found -> !found.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        // 취소·교체된 실행의 캐시를 쓰지 않는다.
        entities.refresh(run);
        AiRunRequest input = mapper.readValue(run.getInputSnapshot(), AiRunRequest.class);
        var member = inquiry.getRequester();
        List<Integer> policies = jdbc.queryForList("""
                SELECT m.policy_revision FROM member m JOIN department d ON d.id=m.department_id
                WHERE m.id=? AND m.deleted_at IS NULL AND d.deleted_at IS NULL
                """, Integer.class, member.getId());
        RunChecks.checkAccess(runId, run, inquiry, input, policies);
        RunChecks.checkActive(run, inquiry, input, inquiryVersion, clock);
        return new RunAccess(run, inquiry, input);
    }
}
