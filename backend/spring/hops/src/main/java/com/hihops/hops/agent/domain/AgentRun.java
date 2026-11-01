package com.hihops.hops.agent.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import com.hihops.hops.inquiry.domain.Inquiry;
import com.hihops.hops.inquiry.domain.Message;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * AI 실행. 상담 분석 또는 Curator 지식화 1회 실행 기록이다 (docs/ERD.md "AI 실행 agent_run").
 *
 * <p>실행 ID는 Spring이 저장할 때 만들고(UUIDv7), 이 ID를 AI 서버에 넘긴다. 그래서 DB 기본값이 없다.
 * inputVersion은 실행 당시 문의 버전이다. 결과가 늦게 와서 문의 버전이 바뀌었으면 Spring이 결과를 버린다.
 *
 * <p>공통 부모를 상속하지 않는 이유: 이 테이블은 수정일시는 있지만 삭제일시가 없다(실행 기록은 지우지 않음).
 * BaseEntity(삭제일시 있음)·CreatedOnlyEntity(수정일시 없음) 어느 쪽과도 맞지 않아 컬럼을 직접 둔다.
 *
 * <p>아직 매핑하지 않은 컬럼: ticket_id, trigger_work_log_id. 티켓·처리 기록 Entity를 만들 때 추가한다.
 */
@Entity
@Table(name = "agent_run")
public class AgentRun {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inquiry_id", nullable = false)
    private Inquiry inquiry;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trigger_message_id")
    private Message triggerMessage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AgentTask task;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private AgentRoute route;

    @Enumerated(EnumType.STRING)
    @Column(name = "run_status", nullable = false, length = 30)
    private RunStatus runStatus;

    @Column(name = "input_version", nullable = false)
    private int inputVersion;

    @Column(name = "model_version", nullable = false, length = 100)
    private String modelVersion;

    @Column(name = "prompt_version", nullable = false, length = 50)
    private String promptVersion;

    @Column(name = "schema_version", nullable = false, length = 50)
    private String schemaVersion;

    @Column(name = "trace_id", length = 100)
    private String traceId;

    // AI가 제안한 판단(Spring 검증 전). 구조는 에이전트 계약(contracts/agents)에서 정해지면 전용 클래스로 바꾼다.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String result;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validation_result", columnDefinition = "jsonb")
    private String validationResult;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AgentRun() {
    }

    public AgentRun(Inquiry inquiry, Message triggerMessage, AgentTask task, RunStatus runStatus, int inputVersion,
            String modelVersion, String promptVersion, String schemaVersion, Instant startedAt) {
        this.inquiry = inquiry;
        this.triggerMessage = triggerMessage;
        this.task = task;
        this.runStatus = runStatus;
        this.inputVersion = inputVersion;
        this.modelVersion = modelVersion;
        this.promptVersion = promptVersion;
        this.schemaVersion = schemaVersion;
        this.startedAt = startedAt;
    }

    public UUID getId() {
        return id;
    }

    public Inquiry getInquiry() {
        return inquiry;
    }

    public Message getTriggerMessage() {
        return triggerMessage;
    }

    public AgentTask getTask() {
        return task;
    }

    public AgentRoute getRoute() {
        return route;
    }

    public RunStatus getRunStatus() {
        return runStatus;
    }

    public int getInputVersion() {
        return inputVersion;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getResult() {
        return result;
    }

    public String getValidationResult() {
        return validationResult;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
