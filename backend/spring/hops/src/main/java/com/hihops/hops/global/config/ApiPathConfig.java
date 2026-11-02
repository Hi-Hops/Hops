package com.hihops.hops.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 공개 API 주소의 공통 접두사 {@value #PREFIX}를 한곳에서 붙인다 (WIRE_CONTRACT 12절).
 *
 * <p>컨트롤러에는 접두사 뒤 주소만 적는다. 예: {@code @RequestMapping("/auth")} → 실제 주소 {@code /api/v2/auth}.
 * 버전이 바뀌면 이 상수 하나만 고치면 된다(아파트 동 번호를 건물 입구에만 붙이고 호수만 문에 적는 것과 같다).
 *
 * <p>우리 패키지({@code com.hihops.hops})의 {@code @RestController}에만 붙인다.
 * Swagger(/v3/api-docs, /swagger-ui)와 Spring 기본 오류(/error)는 다른 패키지라 주소가 그대로다.
 */
@Configuration
public class ApiPathConfig implements WebMvcConfigurer {

    /** SecurityConfig의 공개 주소, Refresh 쿠키 Path처럼 실제 주소가 필요한 곳은 이 상수를 이어 붙인다. */
    public static final String PREFIX = "/api/v2";

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(PREFIX, HandlerTypePredicate.forBasePackage("com.hihops.hops")
                .and(HandlerTypePredicate.forAnnotation(RestController.class)));
    }
}
