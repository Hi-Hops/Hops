package com.hihops.hops.knowledge.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import tools.jackson.databind.JsonNode;

@Component
public class QueryEmbedding {
    public static final String MODEL = "text-embedding-3-small";
    public static final int DIMENSIONS = 1536;
    private final RestClient client;
    private final String apiKey;

    public QueryEmbedding(@Value("${hops.search.embedding-api-key:}") String apiKey) {
        var http = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.client = RestClient.builder().requestFactory(factory)
                .baseUrl("https://api.openai.com/v1").build();
        this.apiKey = apiKey;
    }

    public String embed(String query) {
        if (apiKey.isBlank()) {
            throw new DataAccessResourceFailureException("검색 임베딩 자격 증명이 없습니다");
        }
        JsonNode response;
        try {
            response = client.post().uri("/embeddings").headers(headers -> headers.setBearerAuth(apiKey))
                    .body(Map.of("model", MODEL, "dimensions", DIMENSIONS, "input", query))
                    .retrieve().body(JsonNode.class);
        } catch (RestClientException exception) {
            // 제공자 오류의 요청 본문이나 자격 증명을 호출자 또는 로그에 전달하지 않는다.
            throw new DataAccessResourceFailureException("검색 임베딩 호출 실패");
        }
        if (response == null || !MODEL.equals(response.path("model").asString())
                || response.path("data").size() != 1) {
            throw new DataAccessResourceFailureException("검색 임베딩 응답 모델 불일치");
        }
        JsonNode vector = response.path("data").path(0).path("embedding");
        if (!vector.isArray() || vector.size() != DIMENSIONS) {
            throw new DataAccessResourceFailureException("검색 임베딩 응답 차원 불일치");
        }
        List<String> values = new ArrayList<>(DIMENSIONS);
        double norm = 0;
        for (JsonNode value : vector) {
            double number = value.asDouble();
            if (!value.isNumber() || !Float.isFinite((float) number)) {
                throw new DataAccessResourceFailureException("검색 임베딩 응답 값 오류");
            }
            norm += number * number;
            values.add(Float.toString((float) number));
        }
        if (norm == 0) {
            throw new DataAccessResourceFailureException("검색 임베딩 응답이 영벡터입니다");
        }
        return "[" + String.join(",", values) + "]";
    }
}
