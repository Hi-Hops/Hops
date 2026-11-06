package com.hihops.hops.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * 상담 실행 접수 한도의 기본값이 AI 서버가 받는 형식(contracts/agents/README.md ExecutionLimits)과 맞는지 확인한다.
 * 스프링 컨텍스트나 DB 없이 application.properties만 읽는다.
 */
class AnalysisRunPropertiesTest {

    // WIRE_CONTRACT: 호출당 출력 최대 1,500토큰. AI 서버가 호출마다 따로 적용한다.
    private static final int OUTPUT_TOKENS_PER_CALL = 1_500;

    private static AnalysisRunProperties load() throws IOException {
        Properties properties = new Properties();
        try (InputStream in = new ClassPathResource("application.properties").getInputStream()) {
            properties.load(in);
        }
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind("hops.agent.run", AnalysisRunProperties.class).get();
    }

    @Test
    void 출력_토큰_한도는_호출당_상한이_아니라_실행_합계다() throws IOException {
        var limits = load().limits();

        // 호출당 값(1,500)을 그대로 보내면 AI의 실행 합계 한도에 걸려 모델 호출 두세 번 만에 BUDGET_EXCEEDED가 난다
        assertThat(limits.maxOutputTokens()).isEqualTo(limits.maxModelCalls() * OUTPUT_TOKENS_PER_CALL);
    }

    @Test
    void 기본_기한과_호출_한도는_계약_초기_상한이다() throws IOException {
        var properties = load();

        assertThat(properties.deadline()).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.limits().maxModelCalls()).isEqualTo(8);
        assertThat(properties.limits().maxRetries()).isEqualTo(2);
    }

    @Test
    void 내부_판단_경계는_기본으로_꺼져_있다() throws IOException {
        // 켜려면 SECURITY.md 개정과 보안 리뷰가 먼저다. 기본값이 바뀌면 이 테스트가 막는다.
        assertThat(load().internalJudgement().enabled()).isFalse();
    }

    @Test
    void 내부_판단_경계는_보안_리뷰_기록_없이는_켤_수_없다() {
        var validator = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();

        for (String reference : new String[] {null, "", "   "}) {
            var violations = validator.validate(new AnalysisRunProperties.InternalJudgement(true, reference));
            assertThat(violations).as("reference=" + reference).hasSize(1);
        }
        assertThat(validator.validate(new AnalysisRunProperties.InternalJudgement(true, "보안 리뷰 PR 000"))).isEmpty();
        // 꺼져 있으면 기록이 없어도 된다(기본).
        assertThat(validator.validate(new AnalysisRunProperties.InternalJudgement(false, ""))).isEmpty();
        assertThat(validator.validate(new AnalysisRunProperties.InternalJudgement(false, null))).isEmpty();
    }

    @org.springframework.boot.context.properties.EnableConfigurationProperties(AnalysisRunProperties.class)
    static class PropertiesConfig {
    }

    private org.springframework.boot.test.context.runner.ApplicationContextRunner runner(String... extra) {
        var base = new java.util.ArrayList<>(java.util.List.of(
                "hops.agent.run.deadline=60s",
                "hops.agent.run.limits.max-model-calls=8", "hops.agent.run.limits.max-input-tokens=60000",
                "hops.agent.run.limits.max-output-tokens=12000", "hops.agent.run.limits.max-tool-calls=10",
                "hops.agent.run.limits.max-retries=2", "hops.agent.run.model-version=pending",
                "hops.agent.run.prompt-version=pending", "hops.agent.run.schema-version=hops-system-1"));
        base.addAll(java.util.List.of(extra));
        return new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration.class))
                .withUserConfiguration(PropertiesConfig.class).withPropertyValues(base.toArray(String[]::new));
    }

    @Test
    void 보안_리뷰_기록_없이_켠_서버는_시작하지_않는다() {
        runner("hops.agent.run.internal-judgement.enabled=true").run(context -> assertThat(context).hasFailed());
        runner("hops.agent.run.internal-judgement.enabled=true", "hops.agent.run.internal-judgement.review-reference= ")
                .run(context -> assertThat(context).hasFailed());
        runner("hops.agent.run.internal-judgement.enabled=true", "hops.agent.run.internal-judgement.review-reference=SEC-REVIEW-1")
                .run(context -> assertThat(context).hasNotFailed());
        // 아무 설정이 없으면 꺼짐이고 정상 시작한다.
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AnalysisRunProperties.class).internalJudgement().enabled()).isFalse();
        });
    }
}
