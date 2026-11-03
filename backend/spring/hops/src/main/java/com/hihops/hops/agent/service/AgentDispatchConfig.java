package com.hihops.hops.agent.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AgentDispatchConfig {
    @Bean
    public ThreadPoolTaskExecutor agentDispatchExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2); executor.setMaxPoolSize(4); executor.setQueueCapacity(16);
        executor.setThreadNamePrefix("agent-dispatch-"); executor.setDaemon(true);
        return executor;
    }
}
