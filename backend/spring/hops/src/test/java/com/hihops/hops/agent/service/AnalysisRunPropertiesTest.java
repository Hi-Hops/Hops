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
}
