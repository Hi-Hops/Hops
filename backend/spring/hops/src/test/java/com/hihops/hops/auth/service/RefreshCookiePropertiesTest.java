package com.hihops.hops.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * Refresh 쿠키 Secure 설정 (WIRE_CONTRACT 12.1: 운영 HTTPS는 Secure, 로컬 예외는 환경 설정으로만 분리).
 */
class RefreshCookiePropertiesTest {

    @Test
    void 설정이_없으면_Secure를_켠다() {
        // 운영에서 설정을 빠뜨려도 쿠키가 평문 HTTP로 새지 않게 안전한 쪽이 기본값이다.
        RefreshCookieProperties properties = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("hops.auth.refresh-cookie", RefreshCookieProperties.class);

        assertThat(properties.secure()).isTrue();
    }

    @Test
    void 운영_환경변수_이름으로_값을_바꿀_수_있다() {
        // compose·운영 서버가 쓰는 이름. 점은 _로, 하이픈(refresh-cookie)은 빼고 대문자로 쓴다 (Spring 환경변수 규칙).
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("test-systemEnvironment",
                Map.of("HOPS_AUTH_REFRESHCOOKIE_SECURE", "false")));

        RefreshCookieProperties properties = new Binder(ConfigurationPropertySources.get(environment))
                .bindOrCreate("hops.auth.refresh-cookie", RefreshCookieProperties.class);

        assertThat(properties.secure()).isFalse();
    }
}
