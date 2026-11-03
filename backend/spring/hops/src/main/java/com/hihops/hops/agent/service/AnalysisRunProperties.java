package com.hihops.hops.agent.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
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
 * 초기 값은 WIRE_CONTRACT의 AI 실행 초기 상한(전체 60초, 호출 8회, 재시도 2회, 출력 1,500토큰)을 따른다.
 *
 * @param deadline      접수 시각부터 expiresAt까지
 * @param limits        AiRunRequest.limits
 * @param modelVersion  agent_run.model_version에 남길 값. 모델은 AI 서버가 정하므로 확정 전까지 자리값을 둔다
 * @param promptVersion agent_run.prompt_version에 남길 값
 * @param schemaVersion agent_run.schema_version에 남길 값
 */
@Validated
@ConfigurationProperties(prefix = "hops.agent.run")
public record AnalysisRunProperties(@NotNull Duration deadline, @NotNull @Valid ExecutionLimits limits,
        @NotBlank String modelVersion, @NotBlank String promptVersion, @NotBlank String schemaVersion) {

    @AssertTrue(message = "deadline은 양수여야 합니다")
    public boolean isDeadlineValid() {
        return deadline != null && deadline.toMillis() > 0;
    }
}
