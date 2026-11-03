package com.hihops.hops.auth.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Refresh 쿠키 설정. {@code hops.auth.refresh-cookie.secure}에서 읽는다 (WIRE_CONTRACT 12.1).
 *
 * <p>Secure 쿠키는 HTTPS 연결에서만 오간다. 요청이 https였는지로 정하면 앞단 프록시 설정(X-Forwarded-Proto)에 따라
 * 운영에서도 Secure가 빠질 수 있어서, 환경 설정으로만 정한다. 설정이 없으면 true(안전한 쪽).
 * 로컬 http 개발만 false로 끈다: PC 실행은 application-db.local.properties, Docker는 백엔드 .env, 테스트는
 * application-test.properties. 공통 application.properties에 false를 넣으면 운영까지 꺼지므로 넣지 않는다.
 */
@ConfigurationProperties(prefix = "hops.auth.refresh-cookie")
public record RefreshCookieProperties(@DefaultValue("true") boolean secure) {
}
