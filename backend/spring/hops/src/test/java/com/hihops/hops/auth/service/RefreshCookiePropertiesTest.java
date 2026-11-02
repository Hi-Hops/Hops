package com.hihops.hops.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

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
}
