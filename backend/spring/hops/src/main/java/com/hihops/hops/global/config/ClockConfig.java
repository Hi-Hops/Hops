package com.hihops.hops.global.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 현재 시각을 주는 시계. 서비스가 {@code Instant.now()} 대신 이 Bean을 받아 쓰면
 * 테스트에서 시간을 앞으로 돌려 "10초 뒤"·"7일 뒤" 같은 상황을 바로 확인할 수 있다.
 */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC(); // API·DB 시각 기준이 UTC다 (README "시각")
    }
}
