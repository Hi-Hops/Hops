package com.hihops.hops.auth.service;

import java.time.Duration;

import org.springframework.http.ResponseCookie;

import com.hihops.hops.auth.service.RefreshTokenService.IssuedRefreshToken;
import com.hihops.hops.global.config.ApiPathConfig;

/**
 * Refresh Token을 담는 쿠키 모양을 한 곳에서 정한다. 로그인·refresh·로그아웃이 같은 이름·경로를 써야
 * 브라우저가 같은 쿠키로 알아보고 덮어쓰거나 지운다.
 *
 * <p>HTTP 요청 객체는 받지 않는다. HTTPS 여부만 Controller가 알려주면 되므로 서비스 계층에 두어도 웹 코드에 묶이지 않는다.
 */
public final class RefreshCookies {

    public static final String NAME = "hops_refresh";

    // 이 경로 아래(/api/v2/auth/login·refresh·logout) 요청에만 브라우저가 쿠키를 붙인다. 업무 API에는 따라가지 않는다 (WIRE_CONTRACT 12절).
    private static final String PATH = ApiPathConfig.PREFIX + "/auth";

    private RefreshCookies() {
    }

    /**
     * @param secure HTTPS 요청이면 true. 운영(HTTPS)에서는 Secure로 암호화된 연결에서만 쿠키가 오가고,
     *               로컬 http 개발에서는 Secure를 빼야 브라우저가 쿠키를 저장한다.
     */
    public static ResponseCookie issue(IssuedRefreshToken token, boolean secure) {
        return build(token.value(), token.maxAge(), secure);
    }

    /** 로그아웃 시: 같은 이름·경로·속성에 빈 값과 Max-Age=0을 보내면 브라우저가 쿠키를 지운다. */
    public static ResponseCookie expire(boolean secure) {
        return build("", Duration.ZERO, secure);
    }

    // 발급과 삭제가 같은 속성을 쓰도록 한곳에서 만든다. 속성이 다르면 브라우저가 다른 쿠키로 보고 지우지 않는다.
    private static ResponseCookie build(String value, Duration maxAge, boolean secure) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)   // 프론트 JS(document.cookie)가 읽지 못한다 → XSS로 훔칠 수 없음
                .secure(secure)
                .sameSite("Lax")  // 다른 사이트에서 몰래 보내는 POST에는 붙지 않는다 (CSRF 1차 방어)
                .path(PATH)
                .maxAge(maxAge)
                .build();
    }
}
