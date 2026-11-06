package com.hihops.hops.agent.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import com.hihops.hops.agent.dto.AiRunRequest.ExecutionLimits;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 상담 실행 접수 설정. application.properties의 {@code hops.agent.run.*}에서 읽는다.
 *
 * <p>expiresAt·limits는 Spring이 정한 실행 전체 한도이며 역할 전환·재시도에서 초기화하지 않는다 (contracts/agents/README.md).
 * 초기 값은 WIRE_CONTRACT의 AI 실행 초기 상한(전체 60초, 호출 8회, 재시도 2회, 호출당 출력 1,500토큰)을 따른다.
 * 출력 토큰은 호출당 값이 아니라 실행 합계로 보낸다(호출 수 x 호출당 상한). AI 서버가 호출당 상한은 따로 적용한다.
 *
 * @param deadline      접수 시각부터 expiresAt까지
 * @param limits        AiRunRequest.limits
 * @param modelVersion  agent_run.model_version에 남길 값. 모델은 AI 서버가 정하므로 확정 전까지 자리값을 둔다
 * @param promptVersion agent_run.prompt_version에 남길 값
 * @param schemaVersion agent_run.schema_version에 남길 값
 * @param internalJudgement 내부 판단 경계(옵션). 기본 꺼짐이며 SECURITY.md 개정과 보안 리뷰 전에는 켜지 않는다
 */
@Validated
@ConfigurationProperties(prefix = "hops.agent.run")
public record AnalysisRunProperties(@NotNull Duration deadline, @NotNull @Valid ExecutionLimits limits,
        @NotBlank String modelVersion, @NotBlank String promptVersion, @NotBlank String schemaVersion,
        @DefaultValue @Valid InternalJudgement internalJudgement) {

    /**
     * 직원 상담 실행에서 담당자 전용 자료를 AI가 내부 판단 입력으로 읽게 할지(docs/ai/AGENT_ARCHITECTURE.md 9절 옵션 프로필).
     * 서버 설정으로만 켠다. 요청·모델은 켤 수 없다. 켜면 실행 입력에 accessContext.retrievalScopes가 실리고 지식 검색·관계 조회가
     * 주담당자 조건 없이 담당자 전용 자료를 돌려준다. 사용자에게 닿는 응답·근거 검증은 그대로 엄격하다.
     */
    public record InternalJudgement(boolean enabled, String reviewReference) {
        /**
         * 켜려면 SECURITY.md 개정과 보안 리뷰를 확인했다는 기록(리뷰·PR 번호 등)을 함께 설정해야 한다.
         * 비어 있으면 서버가 시작하지 않는다. 설정 실수로 켜지는 것을 막고, 켠 근거를 로그에 남기려는 장치다.
         */
        @AssertTrue(message = "내부 판단 경계를 켜려면 hops.agent.run.internal-judgement.review-reference에 보안 리뷰 기록을 적어야 합니다")
        public boolean isReviewReferenceRecorded() {
            return !enabled || (reviewReference != null && !reviewReference.isBlank());
        }
    }

    @AssertTrue(message = "deadline은 양수여야 합니다")
    public boolean isDeadlineValid() {
        return deadline != null && deadline.toMillis() > 0;
    }
}
