package com.hihops.hops.agent.domain;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.hihops.hops.global.entity.CreatedOnlyEntity;

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
 * AI 실행 단계. AI 실행 안에서 어느 에이전트가 무엇을 했는지 단계별로 남긴다 (docs/ERD.md "AI 실행 단계 agent_step").
 *
 * <p>채팅의 진행 문구(displaySummary, 예: "문서 검색 중…")와 실패 단계 추적에 쓴다. 추가만 하는 기록이라
 * {@link CreatedOnlyEntity}를 상속한다.
 *
 * <p>stepType·stepStatus를 enum이 아닌 문자열로 두는 이유: ERD에 값 이름이 정해져 있지 않고 AI 서버가 값을 정한다
 * (현재 AI 코드는 INTAKE·SEARCH·DIAGNOSE 등을 보낸다). enum으로 고정하면 AI가 새 단계를 추가할 때 저장이 실패한다.
 */
@Entity
@Table(name = "agent_step",
        uniqueConstraints = @UniqueConstraint(name = "uk_agent_step_run_sequence", columnNames = {"agent_run_id", "sequence_no"}))
public class AgentStep extends CreatedOnlyEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "agent_run_id", nullable = false)
    private AgentRun agentRun;

    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "agent_type", nullable = false, length = 30)
    private AgentType agentType;

    @Column(name = "step_type", nullable = false, length = 30)
    private String stepType;

    @Column(name = "tool_name", length = 100)
    private String toolName;

    @Column(name = "step_status", nullable = false, length = 30)
    private String stepStatus;

    @Column(name = "display_summary", nullable = false, length = 300)
    private String displaySummary;

    @Column(name = "reason_summary", length = 1000)
    private String reasonSummary;

    // 마스킹된 도구 입출력. 구조는 에이전트 계약에서 정해지면 전용 클래스로 바꾼다.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "step_data", columnDefinition = "jsonb")
    private String stepData;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 1;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected AgentStep() {
    }

    public AgentStep(AgentRun agentRun, int sequenceNo, AgentType agentType, String stepType, String stepStatus,
            String displaySummary, Instant startedAt) {
        this.agentRun = agentRun;
        this.sequenceNo = sequenceNo;
        this.agentType = agentType;
        this.stepType = stepType;
        this.stepStatus = stepStatus;
        this.displaySummary = displaySummary;
        this.startedAt = startedAt;
    }

    public AgentRun getAgentRun() {
        return agentRun;
    }

    public int getSequenceNo() {
        return sequenceNo;
    }

    public AgentType getAgentType() {
        return agentType;
    }

    public String getStepType() {
        return stepType;
    }

    public String getToolName() {
        return toolName;
    }

    public String getStepStatus() {
        return stepStatus;
    }

    public String getDisplaySummary() {
        return displaySummary;
    }

    public String getReasonSummary() {
        return reasonSummary;
    }

    public String getStepData() {
        return stepData;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
