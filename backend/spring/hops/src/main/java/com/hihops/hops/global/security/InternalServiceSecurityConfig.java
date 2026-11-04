package com.hihops.hops.global.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import com.hihops.hops.global.exception.ErrorCode;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** AI→Spring 전용 인증. 사용자 JWT·쿠키 및 Spring→AI 토큰으로 내부 쓰기를 허용하지 않는다. */
@Configuration
public class InternalServiceSecurityConfig {
    @Bean
    @Order(1)
    SecurityFilterChain internalServiceChain(HttpSecurity http, SecurityErrorResponder errors,
            @Value("${SPRING_SERVICE_TOKEN:}") String token) throws Exception {
        if (!token.isEmpty()) {
            try {
                if (!token.matches("[A-Za-z0-9_-]+") || Base64.getUrlDecoder().decode(token).length < 32) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("SPRING_SERVICE_TOKEN은 32바이트 이상 base64url 값이어야 합니다");
            }
        }
        http.securityMatcher("/internal/v2/**")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .addFilterBefore(new ServiceBearerFilter(token, errors), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    // Filter Bean으로 등록하지 않는다. 내부 security chain에서만 실행한다.
    static final class ServiceBearerFilter extends OncePerRequestFilter {
        private final byte[] expected;
        private final SecurityErrorResponder errors;

        ServiceBearerFilter(String token, SecurityErrorResponder errors) {
            this.expected = token.isEmpty() ? new byte[0] : ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
            this.errors = errors;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String header = request.getHeader("Authorization");
            if (expected.length == 0 || header == null
                    || !MessageDigest.isEqual(expected, header.getBytes(StandardCharsets.UTF_8))) {
                errors.write(response, ErrorCode.UNAUTHENTICATED);
                return;
            }
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("hops-ai", null, List.of()));
            SecurityContextHolder.setContext(context);
            chain.doFilter(request, response);
        }
    }
}
