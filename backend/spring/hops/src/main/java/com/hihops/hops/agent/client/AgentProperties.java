package com.hihops.hops.agent.client;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Python AI 서버 연결 설정. application.properties의 {@code hops.agent.*}에서 읽는다.
 *
 * @param baseUrl        Python FastAPI 서버 주소. 로컬 기본값은 Uvicorn 기본 포트(8000)
 * @param connectTimeout 서버에 연결하는 데 기다릴 최대 시간. 서버가 꺼져 있으면 이 시간 뒤 실패한다
 * @param readTimeout    응답을 기다릴 최대 시간. 에이전트 전체 실행 제한 90초(contracts/agents/README.md §6)에 맞춘다
 */
@ConfigurationProperties(prefix = "hops.agent")
public record AgentProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {
}
