package com.hihops.hops.agent.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * AI 서버로의 실행 전달 설정. application.properties의 {@code hops.agent.dispatch-*}에서 읽는다.
 *
 * @param dispatchEnabled 끄면 AgentRunQueued를 받아도 AI에 보내지 않는다. 실행은 QUEUED로 남는다(AI 서버 없이 Spring만 띄울 때, 테스트)
 * @param dispatchPollInterval AI 실행 상태를 다시 확인하는 간격
 * @param dispatchDeadlineGrace 입력의 expiresAt 뒤에 더 기다릴 시간. AI가 기한 직후 쓴 종료 결과를 받기 위한 여유다
 */
@ConfigurationProperties(prefix = "hops.agent")
public record AgentDispatchProperties(@DefaultValue("true") boolean dispatchEnabled,
        @DefaultValue("500ms") Duration dispatchPollInterval, @DefaultValue("5s") Duration dispatchDeadlineGrace) {
}
