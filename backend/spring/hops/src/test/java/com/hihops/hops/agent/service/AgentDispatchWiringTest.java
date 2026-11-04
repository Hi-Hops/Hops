package com.hihops.hops.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

import com.hihops.hops.agent.client.AgentRunClient;
import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.agent.domain.AgentTask;
import com.hihops.hops.agent.repository.AgentRunRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실행 전달 빈들이 스프링에서 조립되는지 확인한다. DB 없이 필요한 협력 객체만 가짜로 등록한다.
 * 결과 처리기가 하나도 없는 환경(상담 처리기가 등록되기 전)에서도 기동돼야 하고, 설정 기본값과 재정의가 읽혀야 한다.
 */
class AgentDispatchWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AgentDispatchConfig.class)
            .withBean(AgentRunRepository.class, () -> mock(AgentRunRepository.class))
            .withBean(AgentRunClient.class, () -> mock(AgentRunClient.class))
            .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(AgentRunDispatcher.class);

    @Test
    void 결과_처리기가_없어도_기동되고_기본_설정이_적용된다() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(AgentRunDispatcher.class);
            AgentDispatchProperties properties = context.getBean(AgentDispatchProperties.class);
            assertThat(properties.dispatchEnabled()).isTrue();
            assertThat(properties.dispatchPollInterval()).isEqualTo(Duration.ofMillis(500));
            assertThat(properties.dispatchDeadlineGrace()).isEqualTo(Duration.ofSeconds(5));
        });
    }

    @Test
    void 설정값으로_전달을_끄고_간격을_바꿀_수_있다() {
        runner.withPropertyValues("hops.agent.dispatch-enabled=false", "hops.agent.dispatch-poll-interval=2s")
                .run(context -> {
                    AgentDispatchProperties properties = context.getBean(AgentDispatchProperties.class);
                    assertThat(properties.dispatchEnabled()).isFalse();
                    assertThat(properties.dispatchPollInterval()).isEqualTo(Duration.ofSeconds(2));
                });
    }

    @Test
    void 결과_처리기_빈을_등록하면_함께_조립된다() {
        runner.withBean(AgentRunResultHandler.class, () -> new AgentRunResultHandler() {
            @Override
            public AgentTask task() {
                return AgentTask.INQUIRY_ANALYSIS;
            }

            @Override
            public void verifyAccess(AgentRun run, JsonNode input) {
            }

            @Override
            public String apply(AgentRun run, JsonNode input, JsonNode result) {
                return "{}";
            }
        }).run(context -> assertThat(context).hasNotFailed().hasSingleBean(AgentRunDispatcher.class));
    }

    @Test
    void 같은_작업_종류의_처리기가_둘이면_기동을_거부한다() {
        AgentRunResultHandler first = handler();
        AgentRunResultHandler second = handler();
        runner.withBean("first", AgentRunResultHandler.class, () -> first)
                .withBean("second", AgentRunResultHandler.class, () -> second)
                .run(context -> assertThat(context).hasFailed());
    }

    private static AgentRunResultHandler handler() {
        return new AgentRunResultHandler() {
            @Override
            public AgentTask task() {
                return AgentTask.INQUIRY_ANALYSIS;
            }

            @Override
            public void verifyAccess(AgentRun run, JsonNode input) {
            }

            @Override
            public String apply(AgentRun run, JsonNode input, JsonNode result) {
                return "{}";
            }
        };
    }
}
