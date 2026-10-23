package com.hihops.hops.global.security;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CORS로 허용할 프론트 주소 목록. application.properties의 {@code hops.cors.allowed-origins}에서 읽는다.
 *
 * <p>CORS: 브라우저는 다른 주소(포트가 달라도 다른 주소)의 서버를 부르는 것을 기본으로 막는다.
 * React(예: localhost:5173)가 Spring(localhost:8080)의 API를 부르려면 Spring이 그 주소를 허용해야 한다.
 * 운영 주소가 정해지면 코드 수정 없이 설정 값만 바꾼다.
 */
@ConfigurationProperties(prefix = "hops.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
