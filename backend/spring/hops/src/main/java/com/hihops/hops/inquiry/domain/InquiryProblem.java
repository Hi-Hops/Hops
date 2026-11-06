package com.hihops.hops.inquiry.domain;

import java.time.Instant;

import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.global.entity.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 문의 문제. 복합 문의를 나눈 '문제' 단위다 (docs/ERD.md "문의 문제 inquiry_problem").
 *
 * <p>예: "결재 문서가 안 열리고, 파일 공유 권한도 필요해요" → 문제 1(결재), 문제 2(파일 공유).
 * 문제마다 추가 질문 횟수(clarifyCount, 최대 5회)·티켓 생성·무티켓 해결 확인을 따로 관리한다.
 * 문제 하나에서 티켓은 최대 1개다 (ticket.inquiry_problem_id 중복 불가).
 *
 * <p>의도·처리 유형·시스템·확인 사실은 표준 요청서(standard_request)에 문의 버전별로 두고, 여기에는 진행 상태만 둔다.
 */
@Entity
@Table(name = "inquiry_problem",
        uniqueConstraints = @UniqueConstraint(name = "uk_inquiry_problem_inquiry_problem_no", columnNames = {"inquiry_id", "problem_no"}))
public class InquiryProblem extends BaseEntity {

    /** 문제당 추가 질문 묶음 최대 횟수 (docs/ERD.md clarify_count) */
    public static final int MAX_CLARIFY_COUNT = 5;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inquiry_id", nullable = false)
    private Inquiry inquiry;

    @Column(name = "problem_no", nullable = false)
    private int problemNo;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(name = "problem_status", nullable = false, length = 30)
    private ProblemStatus problemStatus;

    @Column(name = "clarify_count", nullable = false)
    private int clarifyCount = 0;

    // 티켓 생성 작업 결과와 그 기준 문의 버전. 생성 작업이 없으면 둘 다 null이다.
    @Enumerated(EnumType.STRING)
    @Column(name = "creation_outcome", length = 30)
    private CreationOutcome creationOutcome;

    @Column(name = "creation_input_version")
    private Integer creationInputVersion;

    @Column(name = "ticket_error_code", length = 50)
    private String ticketErrorCode;

    @Column(name = "confirm_requested_at")
    private Instant confirmRequestedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    // 이 문제를 판단한 AI 실행. 사람이 직접 만든 문제라면 비어 있을 수 있다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_run_id")
    private AgentRun agentRun;

    protected InquiryProblem() {
    }

    public InquiryProblem(Inquiry inquiry, int problemNo, String title, String detail, ProblemStatus problemStatus,
            AgentRun agentRun) {
        // 무티켓 해결은 문의자가 확인한 시각(resolved_at)이 있어야 해서(ck_inquiry_problem_resolved) 처음부터 만들 수 없다
        if (problemStatus == ProblemStatus.RESOLVED_WITHOUT_TICKET) {
            throw new IllegalArgumentException("무티켓 해결 상태로는 문제를 처음부터 만들 수 없다");
        }
        this.inquiry = inquiry;
        this.problemNo = problemNo;
        this.title = title;
        this.detail = detail;
        this.problemStatus = problemStatus;
        this.agentRun = agentRun;
    }

    public Inquiry getInquiry() {
        return inquiry;
    }

    public int getProblemNo() {
        return problemNo;
    }

    public String getTitle() {
        return title;
    }

    public String getDetail() {
        return detail;
    }

    public ProblemStatus getProblemStatus() {
        return problemStatus;
    }

    public int getClarifyCount() {
        return clarifyCount;
    }

    public CreationOutcome getCreationOutcome() {
        return creationOutcome;
    }

    public Integer getCreationInputVersion() {
        return creationInputVersion;
    }

    public String getTicketErrorCode() {
        return ticketErrorCode;
    }

    public Instant getConfirmRequestedAt() {
        return confirmRequestedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public AgentRun getAgentRun() {
        return agentRun;
    }

    public void respondGuide(boolean resolved, Instant at, AgentRun reanalysis) {
        problemStatus = resolved ? ProblemStatus.RESOLVED_WITHOUT_TICKET : ProblemStatus.CLARIFYING;
        resolvedAt = resolved ? at : null;
        confirmRequestedAt = null;
        if (reanalysis != null) agentRun = reanalysis;
    }

    /**
     * 문제에 추가 질문 묶음 하나를 저장했다. 질문 수를 1 올리고 문의자의 답을 기다리는 상태로 둔다.
     * 문제당 5회 한도는 여기서 막는다 (docs/DATA.md 4절).
     */
    public void recordQuestion(AgentRun run) {
        if (clarifyCount >= MAX_CLARIFY_COUNT) {
            throw new IllegalStateException("문제당 질문은 " + MAX_CLARIFY_COUNT + "회까지다");
        }
        clarifyCount++;
        problemStatus = ProblemStatus.CLARIFYING;
        agentRun = run;
    }

    /** 질문 없이 끝난 분석 결과의 상태(시스템 확인 대기 또는 지원 범위 밖)를 이 문제에 반영한다. */
    public void recordAnalysisOutcome(ProblemStatus status, AgentRun run) {
        if (status != ProblemStatus.SYSTEM_PENDING && status != ProblemStatus.OUT_OF_SCOPE) {
            throw new IllegalArgumentException("분석 결과로 정할 수 없는 상태다: " + status);
        }
        problemStatus = status;
        agentRun = run;
    }

    public void requestGuideConfirmation(AgentRun run, Instant at) {
        problemStatus = ProblemStatus.GUIDED;
        confirmRequestedAt = at;
        resolvedAt = null;
        agentRun = run;
    }
}
