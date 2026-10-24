package com.hihops.hops.agent.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Python AI 서버를 호출할 HTTP 클라이언트(RestClient)를 만든다.
 *
 * <p>연결 방식 (docs/API.md §3): Spring → Python은 HTTP로 실행을 요청하고, Python → Spring 진행 상황은 SSE로 받는다.
 * 실행 요청 경로·요청/응답 형식, SSE 경로·재연결, 서비스 간 인증은 통합 전에 합의하므로 여기서는
 * 주소와 시간 제한이 적용된 클라이언트만 준비한다. 계약이 정해지면 이 클라이언트를 쓰는 AgentClient를 추가한다.
 *
 * <p>사용 예 (계약 확정 후):
 * <pre>
 * agentRestClient.post().uri("/agent-runs").body(request).retrieve().body(AgentRunResponse.class);
 * </pre>
 *
 * <p>OpenAI API 키는 Python 서버가 관리하므로(PRD 6장) Spring에는 두지 않는다.
 */
@Configuration
@EnableConfigurationProperties(AgentProperties.class)
public class AgentClientConfig {

    /** 여러 RestClient가 생길 수 있어 이름으로 구분한다. 주입할 때 @Qualifier(AGENT_REST_CLIENT)를 붙인다. */
    public static final String AGENT_REST_CLIENT = "agentRestClient";

    @Bean
    @Qualifier(AGENT_REST_CLIENT)
    RestClient agentRestClient(RestClient.Builder builder, AgentProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        return builder
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
