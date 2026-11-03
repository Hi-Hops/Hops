package com.hihops.hops.global.security;

import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import com.hihops.hops.auth.service.RefreshCookieProperties;
import com.hihops.hops.global.config.ApiPathConfig;

/**
 * Spring Security 설정: 누가 어떤 주소에 접근할 수 있는지 정한다.
 *
 * <p>인증: 요청의 {@code Authorization: Bearer 토큰}을 {@link JwtConfig} 규칙으로 검증한다.
 * JWT는 Spring만 발급·검증한다(팀 결정). 아래 "열어 둔 주소"를 뺀 요청은 올바른 토큰이 없으면 401이 된다.
 *
 * <p>필터 순서: RequestIdFilter → RequestLoggingFilter → Security(이 설정) → Controller
 */
@Configuration
@EnableConfigurationProperties({CorsProperties.class, RefreshCookieProperties.class})
public class SecurityConfig {

    // 인증 주소. permitAll과 CookieOriginFilter가 같은 값을 쓰도록 한곳에 둔다.
    static final String LOGIN_PATH = ApiPathConfig.PREFIX + "/auth/login";
    static final String REFRESH_PATH = ApiPathConfig.PREFIX + "/auth/refresh";
    static final String LOGOUT_PATH = ApiPathConfig.PREFIX + "/auth/logout";

    /** 로그인 없이 접근할 수 있는 주소. 그 외는 모두 인증이 필요하다. */
    private static final String[] PUBLIC_PATHS = {
            "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**", // Swagger 화면 (5단계)
            "/error"                                                 // Spring 기본 오류 경로
    };

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityErrorResponder errorResponder,
            CorsProperties corsProperties) throws Exception {
        http
                // refresh·logout은 허용한 프론트 주소에서 온 요청만 받는다. CORS 필터보다 먼저 공통 오류 형식으로 막는다.
                .addFilterBefore(new CookieOriginFilter(List.of(REFRESH_PATH, LOGOUT_PATH),
                        corsProperties.allowedOrigins(), errorResponder), CorsFilter.class)
                // 업무 API는 Access를 Authorization 헤더로 보내므로 CSRF 공격 대상이 아니다.
                // 쿠키를 쓰는 refresh·logout은 SameSite=Lax 쿠키와 위 CookieOriginFilter(허용 Origin 검사)로 막는다.
                // 그 밖의 API가 쿠키로 인증하게 바뀌면 CSRF 보호를 다시 설계해야 한다.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults()) // 아래 corsConfigurationSource() 사용
                .httpBasic(AbstractHttpConfigurer::disable) // 브라우저 기본 로그인 창 사용 안 함
                .formLogin(AbstractHttpConfigurer::disable) // Spring 기본 로그인 페이지 사용 안 함
                // JWT는 요청마다 토큰으로 사용자를 확인하므로 서버가 로그인 상태(세션)를 저장하지 않는다.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, LOGIN_PATH).permitAll() // 로그인 전에는 토큰이 없다
                        // Access가 만료된 뒤에도 부르는 곳이라 Bearer 대신 Refresh 쿠키로 확인한다 (AuthController)
                        .requestMatchers(HttpMethod.POST, REFRESH_PATH, LOGOUT_PATH).permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                // Bearer 토큰 검증. 토큰이 틀리거나 만료되면 errorResponder가 401 공통 오류로 응답한다.
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenResolver(ignoringAuthPaths())
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(errorResponder))
                // 401·403도 공통 오류 형식으로 응답
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(errorResponder)
                        .accessDeniedHandler(errorResponder));
        return http.build();
    }

    /**
     * login·refresh·logout에서는 Authorization 헤더를 읽지 않는다. 이 셋은 Bearer가 아니라 비밀번호·Refresh 쿠키로 확인한다.
     * 프론트는 logout에도 Bearer를 붙이는데, 그 토큰이 만료·손상됐다고 컨트롤러 전에 401로 막으면 쿠키·세션이 남는다.
     */
    private static BearerTokenResolver ignoringAuthPaths() {
        PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
        RequestMatcher authPaths = new OrRequestMatcher(
                paths.matcher(HttpMethod.POST, LOGIN_PATH),
                paths.matcher(HttpMethod.POST, REFRESH_PATH),
                paths.matcher(HttpMethod.POST, LOGOUT_PATH));
        DefaultBearerTokenResolver bearer = new DefaultBearerTokenResolver();
        return request -> authPaths.matches(request) ? null : bearer.resolve(request);
    }

    /**
     * CORS 허용 규칙. 허용 주소는 {@link CorsProperties}(설정 파일)에서 읽는다.
     * Authorization 헤더로 토큰을 보내고, 응답의 X-Request-Id를 프론트가 읽을 수 있게 노출한다.
     *
     * <p>allowCredentials: 로그인·refresh·로그아웃은 쿠키를 포함해 보낸다(WIRE_CONTRACT 10절).
     * 이 값이 true면 허용 주소에 "*"를 쓸 수 없고 주소를 하나씩 정확히 적어야 한다(Spring이 막는다).
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties corsProperties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsProperties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "Idempotency-Key", "X-Request-Id", "Last-Event-ID"));
        configuration.setAllowCredentials(true);
        configuration.setExposedHeaders(List.of("X-Request-Id"));
        configuration.setMaxAge(3600L); // 브라우저가 사전 확인(preflight) 결과를 1시간 동안 재사용

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
