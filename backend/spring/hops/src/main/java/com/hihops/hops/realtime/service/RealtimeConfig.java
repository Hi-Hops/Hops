package com.hihops.hops.realtime.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableConfigurationProperties(RealtimeProperties.class)
class RealtimeConfig {
    @Bean
    ThreadPoolTaskScheduler realtimeScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("realtime-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
