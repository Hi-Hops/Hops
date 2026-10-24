package com.hihops.hops.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * Swagger(API 설명·테스트 화면) 설정. 화면 주소: http://localhost:8080/swagger-ui.html
 *
 * <p>Swagger는 Controller 코드를 읽어 설명서를 자동으로 만든다. "지금 코드가 이렇게 동작한다"를 확인하는 용도이며,
 * 팀이 합의한 API 계약의 원본은 별도로 작성하는 OpenAPI YAML이다 (docs/AGENT_DEVELOPMENT.md 11장).
 * 둘이 다르면 YAML이 기준이고 코드를 YAML에 맞춘다.
 *
 * <p>운영 등에서 끄려면 설정에 {@code springdoc.api-docs.enabled=false}, {@code springdoc.swagger-ui.enabled=false}를 넣는다.
 */
@Configuration
public class OpenApiConfig {

    /** Swagger 화면의 Authorize 버튼에서 쓰는 인증 방식 이름. */
    private static final String BEARER_AUTH = "bearerAuth";

    @Bean
    OpenAPI hopsOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Hops API")
                        .version("v1")
                        .description("""
                                사내 IT 문의·티켓 서비스 Spring API. 코드에서 자동 생성한 확인용 문서이며,
                                계약 원본은 팀이 합의한 OpenAPI YAML이다. 오류 응답 형식은 docs/API.md §1을 따른다."""))
                // Authorize 버튼: 로그인으로 받은 JWT를 넣으면 이후 요청에 "Authorization: Bearer <토큰>"이 붙는다.
                .components(new Components().addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                // 모든 API에 기본으로 JWT가 필요하다고 표시한다. 로그인처럼 열린 API는 해당 Controller에서 따로 표시한다.
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }
}
