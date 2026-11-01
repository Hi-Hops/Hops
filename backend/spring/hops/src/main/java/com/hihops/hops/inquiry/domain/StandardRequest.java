package com.hihops.hops.inquiry.domain;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.global.entity.CreatedOnlyEntity;
import com.hihops.hops.itsystem.domain.ItSystem;
import com.hihops.hops.itsystem.domain.SystemFeature;

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
 * 표준 요청서. 문제별 대화를 담당자가 바로 일할 수 있는 요청서로 정리한다 (docs/ERD.md "표준 요청서 standard_request").
 *
 * <p>대화 원문은 메시지에 그대로 두고, 문의 버전마다 새 행을 추가한다(추가만 하는 이력). 그래서 정정 전 정리도 남는다.
 * 확인한 사실(confirmedFacts)·추정(assumptions)·미확인 항목(missingItems)을 나눠서,
 * 모델 추정을 사용자가 말한 사실처럼 담당자에게 넘기지 않는다.
 *
 * <p>JSONB 필드의 기본값("[]", "{}")을 Java에도 두는 이유: JPA는 저장할 때 모든 컬럼 값을 직접 보내서,
 * 값이 null이면 DB 기본값 대신 NULL이 들어가 NOT NULL 제약에 걸린다.
 * 내부 구조는 아직 정해지지 않아 JSON 문자열로 두고, 확정되면 전용 클래스로 바꾼다.
 */
@Entity
@Table(name = "standard_request",
        uniqueConstraints = @UniqueConstraint(name = "uk_standard_request_problem_version",
                columnNames = {"inquiry_problem_id", "inquiry_version"}))
public class StandardRequest extends CreatedOnlyEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inquiry_problem_id", nullable = false)
    private InquiryProblem inquiryProblem;

    @Column(name = "inquiry_version", nullable = false)
    private int inquiryVersion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_run_id")
    private AgentRun agentRun;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RequestIntent intent;

    @Enumerated(EnumType.STRING)
    @Column(name = "work_type", nullable = false, length = 30)
    private WorkType workType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reported_system_id")
    private ItSystem reportedSystem;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reported_feature_id")
    private SystemFeature reportedFeature;

    // Spring이 이 시스템의 주담당자에게 티켓을 배정한다. 확인 전이면 null.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "responsible_system_id")
    private ItSystem responsibleSystem;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "system_candidates", nullable = false, columnDefinition = "jsonb")
    private String systemCandidates = "[]";

    @Column(nullable = false, columnDefinition = "TEXT")
    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "confirmed_facts", nullable = false, columnDefinition = "jsonb")
    private String confirmedFacts = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String assumptions = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "missing_items", nullable = false, columnDefinition = "jsonb")
    private String missingItems = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "type_detail", nullable = false, columnDefinition = "jsonb")
    private String typeDetail = "{}";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "related_knowledge", nullable = false, columnDefinition = "jsonb")
    private String relatedKnowledge = "[]";

    protected StandardRequest() {
    }

    public StandardRequest(InquiryProblem inquiryProblem, int inquiryVersion, AgentRun agentRun, RequestIntent intent,
            WorkType workType, ItSystem reportedSystem, SystemFeature reportedFeature, ItSystem responsibleSystem,
            String summary) {
        this.inquiryProblem = inquiryProblem;
        this.inquiryVersion = inquiryVersion;
        this.agentRun = agentRun;
        this.intent = intent;
        this.workType = workType;
        this.reportedSystem = reportedSystem;
        this.reportedFeature = reportedFeature;
        this.responsibleSystem = responsibleSystem;
        this.summary = summary;
    }

    public InquiryProblem getInquiryProblem() {
        return inquiryProblem;
    }

    public int getInquiryVersion() {
        return inquiryVersion;
    }

    public AgentRun getAgentRun() {
        return agentRun;
    }

    public RequestIntent getIntent() {
        return intent;
    }

    public WorkType getWorkType() {
        return workType;
    }

    public ItSystem getReportedSystem() {
        return reportedSystem;
    }

    public SystemFeature getReportedFeature() {
        return reportedFeature;
    }

    public ItSystem getResponsibleSystem() {
        return responsibleSystem;
    }

    public String getSystemCandidates() {
        return systemCandidates;
    }

    public String getSummary() {
        return summary;
    }

    public String getConfirmedFacts() {
        return confirmedFacts;
    }

    public String getAssumptions() {
        return assumptions;
    }

    public String getMissingItems() {
        return missingItems;
    }

    public String getTypeDetail() {
        return typeDetail;
    }

    public String getRelatedKnowledge() {
        return relatedKnowledge;
    }
}
