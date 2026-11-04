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
 * AgentRunClient는 접수·snapshot을, AgentRunEventClient는 내부 SSE를 읽는다.
 * 긴 스트림 읽기가 snapshot 폴링을 막지 않도록 HTTP 클라이언트와 수신 풀을 분리한다.
 *
 * <p>OpenAI API 키는 Python 서버가 관리하므로(PRD 6장) Spring에는 두지 않는다.
 */
@Configuration
@EnableConfigurationProperties(AgentProperties.class)
public class AgentClientConfig {

    /** 여러 RestClient가 생길 수 있어 이름으로 구분한다. 주입할 때 @Qualifier(AGENT_REST_CLIENT)를 붙인다. */
    public static final String AGENT_REST_CLIENT = "agentRestClient";
    public static final String AGENT_SSE_CLIENT = "agentSseClient";

    @Bean
    @Qualifier(AGENT_SSE_CLIENT)
    RestClient agentSseClient(RestClient.Builder builder, AgentProperties properties) {
        var http = java.net.http.HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.readTimeout());
        return builder.baseUrl(properties.baseUrl()).requestFactory(factory).build();
    }

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
