package com.hihops.hops.knowledge.domain;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.global.entity.BaseEntity;
import com.hihops.hops.itsystem.domain.ItSystem;
import com.hihops.hops.itsystem.domain.SystemFeature;
import com.hihops.hops.ticket.domain.TicketWorkLog;
import com.hihops.hops.user.domain.Member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/**
 * 해결 사례. 담당자의 해결·구현 기록으로 만든 사례다 (docs/ERD.md "해결 사례 resolved_case").
 *
 * <p>흐름: Curator가 초안(DRAFT)을 만든다 → 해당 시스템 주담당자가 확인·승인(APPROVED)한다
 * → Vector·Neo4j에 각각 반영한다(vectorStatus·graphStatus). 승인됐고 두 반영이 모두 SUCCEEDED일 때만 검색에 쓴다.
 * 초안 생성·승인·검색 반영은 서로 다른 사실이라 상태를 따로 둔다.
 *
 * <p>원 처리 기록(sourceWorkLog)과는 1:1이다. 처리 기록 하나에서 사례는 최대 하나이고, 작성자는 그 기록의 작성 담당자다.
 *
 * <p>JSONB 목록 필드에 Java 기본값("[]")을 두는 이유는 StandardRequest와 같다 (JPA가 null을 보내 NOT NULL에 걸리지 않게).
 */
@Entity
@Table(name = "resolved_case")
public class ResolvedCase extends BaseEntity {

    @Column(name = "case_number", nullable = false, unique = true, length = 20)
    private String caseNumber;

    @Column(name = "version_no", nullable = false)
    private int versionNo = 1;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_work_log_id", nullable = false, unique = true)
    private TicketWorkLog sourceWorkLog;

    // 검색 범위와 승인할 주담당자를 정하는 시스템
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "it_system_id", nullable = false)
    private ItSystem itSystem;

    // 특정 기능 사례가 아니면 null
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "system_feature_id")
    private SystemFeature systemFeature;

    @Enumerated(EnumType.STRING)
    @Column(name = "case_type", nullable = false, length = 30)
    private CaseType caseType;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "problem_statement", nullable = false, columnDefinition = "TEXT")
    private String problemStatement;

    @Column(columnDefinition = "TEXT")
    private String preconditions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "confirmed_facts", nullable = false, columnDefinition = "jsonb")
    private String confirmedFacts = "[]";

    // 담당자가 확인한 원인. 확인하지 못했으면 null
    @Column(name = "confirmed_cause", columnDefinition = "TEXT")
    private String confirmedCause;

    @Column(name = "action_detail", nullable = false, columnDefinition = "TEXT")
    private String actionDetail;

    @Column(name = "verification_result", nullable = false, columnDefinition = "TEXT")
    private String verificationResult;

    @Column(columnDefinition = "TEXT")
    private String applicability;

    @Column(name = "applied_version", length = 100)
    private String appliedVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "access_level", nullable = false, length = 30)
    private AccessLevel accessLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin_type", nullable = false, length = 30)
    private CaseOrigin originType;

    @Enumerated(EnumType.STRING)
    @Column(name = "case_status", nullable = false, length = 30)
    private CaseStatus caseStatus = CaseStatus.DRAFT;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "curator_run_id")
    private AgentRun curatorRun;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "duplicate_candidates", nullable = false, columnDefinition = "jsonb")
    private String duplicateCandidates = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "relation_candidates", nullable = false, columnDefinition = "jsonb")
    private String relationCandidates = "[]";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by_member_id")
    private Member approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "vector_status", nullable = false, length = 30)
    private IndexStatus vectorStatus = IndexStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "graph_status", nullable = false, length = 30)
    private IndexStatus graphStatus = IndexStatus.PENDING;

    @Column(name = "graph_node_id", length = 100)
    private String graphNodeId;

    protected ResolvedCase() {
    }

    public ResolvedCase(String caseNumber, TicketWorkLog sourceWorkLog, ItSystem itSystem, SystemFeature systemFeature,
            CaseType caseType, String title, String problemStatement, String actionDetail, String verificationResult,
            AccessLevel accessLevel, CaseOrigin originType, AgentRun curatorRun) {
        this.caseNumber = caseNumber;
        this.sourceWorkLog = sourceWorkLog;
        this.itSystem = itSystem;
        this.systemFeature = systemFeature;
        this.caseType = caseType;
        this.title = title;
        this.problemStatement = problemStatement;
        this.actionDetail = actionDetail;
        this.verificationResult = verificationResult;
        this.accessLevel = accessLevel;
        this.originType = originType;
        this.curatorRun = curatorRun;
    }

    public String getCaseNumber() {
        return caseNumber;
    }

    /** 출처 기록은 고정하고 초안의 적용 조건·제한·관계 후보만 보존한다. */
    public void recordDraftMetadata(String applicability, String cause, String appliedVersion, String facts, String relations) {
        this.applicability = applicability;
        this.confirmedCause = cause;
        this.appliedVersion = appliedVersion;
        this.confirmedFacts = facts;
        this.relationCandidates = relations;
    }

    /**
     * 현재 초안의 내용을 고치고 버전을 올린다. 출처·원 기록·적용 시스템·확인된 원인·관계 후보는 바꾸지 않는다.
     * 승인된 사례는 읽기 전용이다.
     */
    public void editDraft(String title, String problemStatement, String actionDetail, String verificationResult,
            String applicability, String appliedVersion, AccessLevel accessLevel, String confirmedFacts) {
        if (caseStatus != CaseStatus.DRAFT) {
            throw new IllegalStateException("승인된 사례는 수정할 수 없다");
        }
        this.title = title;
        this.problemStatement = problemStatement;
        this.actionDetail = actionDetail;
        this.verificationResult = verificationResult;
        this.applicability = applicability;
        this.appliedVersion = appliedVersion;
        this.accessLevel = accessLevel;
        this.confirmedFacts = confirmedFacts;
        this.versionNo++;
    }

    /**
     * 현재 초안을 승인한다. 내용·버전은 그대로 두고 승인자·시각을 남기며, 두 저장소 반영은 대기로 시작한다.
     * 승인은 공개가 아니다. 두 반영이 같은 버전으로 성공해야 검색에 쓴다.
     */
    public void approve(Member approver, Instant at) {
        if (caseStatus != CaseStatus.DRAFT) {
            throw new IllegalStateException("이미 승인된 사례다");
        }
        this.caseStatus = CaseStatus.APPROVED;
        this.approvedBy = approver;
        this.approvedAt = at;
        this.vectorStatus = IndexStatus.PENDING;
        this.graphStatus = IndexStatus.PENDING;
    }

    public int getVersionNo() {
        return versionNo;
    }

    public TicketWorkLog getSourceWorkLog() {
        return sourceWorkLog;
    }

    public ItSystem getItSystem() {
        return itSystem;
    }

    public SystemFeature getSystemFeature() {
        return systemFeature;
    }

    public CaseType getCaseType() {
        return caseType;
    }

    public String getTitle() {
        return title;
    }

    public String getProblemStatement() {
        return problemStatement;
    }

    public String getPreconditions() {
        return preconditions;
    }

    public String getConfirmedFacts() {
        return confirmedFacts;
    }

    public String getConfirmedCause() {
        return confirmedCause;
    }

    public String getActionDetail() {
        return actionDetail;
    }

    public String getVerificationResult() {
        return verificationResult;
    }

    public String getApplicability() {
        return applicability;
    }

    public String getAppliedVersion() {
        return appliedVersion;
    }

    public AccessLevel getAccessLevel() {
        return accessLevel;
    }

    public CaseOrigin getOriginType() {
        return originType;
    }

    public CaseStatus getCaseStatus() {
        return caseStatus;
    }

    public AgentRun getCuratorRun() {
        return curatorRun;
    }

    public String getDuplicateCandidates() {
        return duplicateCandidates;
    }

    public String getRelationCandidates() {
        return relationCandidates;
    }

    public Member getApprovedBy() {
        return approvedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public IndexStatus getVectorStatus() {
        return vectorStatus;
    }

    public IndexStatus getGraphStatus() {
        return graphStatus;
    }

    public String getGraphNodeId() {
        return graphNodeId;
    }
}
