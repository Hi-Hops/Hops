package com.hihops.hops.inquiry.domain;

import java.time.Instant;

import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.global.entity.BaseEntity;
import com.hihops.hops.user.domain.Member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 문의. 대화 세션 1개 = 문의 1건이다 (docs/ERD.md "문의 inquiry").
 *
 * <p>version(문의 버전)은 1부터 시작하고 메시지 추가·정정 때마다 1씩 늘린다.
 * AI 실행은 시작할 때의 버전을 기록하고, 결과가 늦게 와서 버전이 다르면 그 결과를 버린다.
 * JPA {@code @Version}(동시 수정 방지 잠금)과는 다른 업무 값이라 일반 컬럼으로 둔다.
 *
 * <p>currentRun은 지금 이 문의를 분석 중인(마지막으로 시작한) 상담 실행이고, resultRevision은 AI 결과를 적용할 때마다 늘린다.
 * 늦게 온 결과는 문의 버전과 현재 실행이 둘 다 맞아야 적용한다 (docs/ERD.md 6.1절).
 */
@Entity
@Table(name = "inquiry")
public class Inquiry extends BaseEntity {

    @Column(name = "inquiry_number", nullable = false, unique = true, length = 20)
    private String inquiryNumber;

    // 본인 문의만 보이게 하는 기준. 문의 목록에서는 문의자 정보가 필요 없어 LAZY로 둔다.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requester_member_id", nullable = false)
    private Member requester;

    @Column(nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "inquiry_status", nullable = false, length = 30)
    private InquiryStatus inquiryStatus;

    @Column(nullable = false)
    private int version = 1;

    // 상담 실행만 가리킨다. Curator 실행은 처리 기록(trigger_work_log_id)으로 따로 찾는다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_run_id")
    private AgentRun currentRun;

    @Column(name = "result_revision", nullable = false)
    private int resultRevision = 1;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "summary_until_seq")
    private Integer summaryUntilSeq;

    @Column(name = "summary_updated_at")
    private Instant summaryUpdatedAt;

    protected Inquiry() {
    }

    public Inquiry(String inquiryNumber, Member requester, String title, InquiryStatus inquiryStatus) {
        this.inquiryNumber = inquiryNumber;
        this.requester = requester;
        this.title = title;
        this.inquiryStatus = inquiryStatus;
    }

    public String getInquiryNumber() {
        return inquiryNumber;
    }

    public Member getRequester() {
        return requester;
    }

    public String getTitle() {
        return title;
    }

    public InquiryStatus getInquiryStatus() {
        return inquiryStatus;
    }

    public int getVersion() {
        return version;
    }

    public AgentRun getCurrentRun() {
        return currentRun;
    }

    public int getResultRevision() {
        return resultRevision;
    }

    public String getSummary() {
        return summary;
    }

    public Integer getSummaryUntilSeq() {
        return summaryUntilSeq;
    }

    public Instant getSummaryUpdatedAt() {
        return summaryUpdatedAt;
    }

    /** AI 상담 결과를 반영했음을 알린다. 화면은 이 값이 바뀌면 문의 결과를 다시 읽는다. */
    public void recordAnalysisResult() {
        resultRevision++;
    }

    public void resumeAnalysis(AgentRun run) {
        currentRun = run;
        inquiryStatus = InquiryStatus.IN_PROGRESS;
    }
}
