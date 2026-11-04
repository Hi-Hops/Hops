package com.hihops.hops.agent.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** AI 서버로 실행을 전달하고 결과를 기다리는 전용 스레드 풀. 요청 스레드를 AI 응답 시간 동안 붙잡지 않으려고 분리한다. */
@Configuration
@EnableConfigurationProperties(AgentDispatchProperties.class)
public class AgentDispatchConfig {

    /** AgentRunDispatcher가 이름으로 주입받는다. Boot의 기본 applicationTaskExecutor와 섞이지 않게 한다. */
    public static final String AGENT_DISPATCH_EXECUTOR = "agentDispatchExecutor";
    public static final String AGENT_STREAM_EXECUTOR = "agentStreamExecutor";

    @Bean(AGENT_STREAM_EXECUTOR)
    ThreadPoolTaskExecutor agentStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("agent-stream-");
        executor.setDaemon(true);
        return executor;
    }

    @Bean(AGENT_DISPATCH_EXECUTOR)
    ThreadPoolTaskExecutor agentDispatchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 실행 하나가 AI 응답을 기다리며 스레드 하나를 점유한다. 대기 큐가 차면 접수를 실패로 끝낸다.
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(16);
        executor.setThreadNamePrefix("agent-dispatch-");
        executor.setDaemon(true);
        return executor;
    }
}
