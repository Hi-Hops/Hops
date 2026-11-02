package com.hihops.hops.auth.controller;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hihops.hops.auth.dto.LoginRequest;
import com.hihops.hops.auth.dto.LoginResponse;
import com.hihops.hops.auth.service.AccessTokenIssuer;
import com.hihops.hops.auth.service.AuthService;
import com.hihops.hops.auth.service.RefreshCookies;
import com.hihops.hops.auth.service.RefreshTokenService;
import com.hihops.hops.auth.service.RefreshTokenService.IssuedRefreshToken;
import com.hihops.hops.auth.service.RefreshTokenService.Rotated;
import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.user.domain.Member;
import com.hihops.hops.user.dto.CurrentUserResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AccessTokenIssuer accessTokenIssuer;
    private final RefreshTokenService refreshTokenService;

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        Member member = authService.login(request.loginId(), request.password()); // 틀리면 여기서 401
        return withTokens(member, refreshTokenService.issue(member), httpRequest);
    }

    /**
     * Access가 만료되면 프론트가 부른다. 본문 없이 Refresh 쿠키만 받는다 (WIRE_CONTRACT 2절).
     * 쿠키가 없거나 무효면 401 REFRESH_TOKEN_REVOKED → 프론트는 재로그인한다.
     */
    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(
            @CookieValue(name = RefreshCookies.NAME, required = false) String refreshToken,
            HttpServletRequest httpRequest) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_REVOKED);
        }
        Rotated rotated = refreshTokenService.rotate(refreshToken);
        // 토큰이 아니라 DB를 다시 읽는다. 그 사이 바뀐 부서·역할로 새 Access를 만들고, 차단된 회원은 401로 막는다.
        Member member = authService.currentMember(rotated.memberId());
        return withTokens(member, rotated.token(), httpRequest);
    }

    // Access는 본문(JSON)으로, Refresh는 Set-Cookie 헤더로 따로 내려준다. Refresh 원문은 JSON에 넣지 않는다.
    private ResponseEntity<LoginResponse> withTokens(Member member, IssuedRefreshToken refreshToken,
            HttpServletRequest httpRequest) {
        ResponseCookie refreshCookie = RefreshCookies.issue(refreshToken, httpRequest.isSecure());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .body(LoginResponse.of(accessTokenIssuer.issue(member), member));
    }

    /** 토큰 주인의 현재 사용자 정보. 토큰이 없거나 틀리면 이 메서드까지 오지 않고 401이 된다(SecurityConfig). */
    @GetMapping("/me")
    public CurrentUserResponse me(@AuthenticationPrincipal Jwt jwt) {
        UUID memberId = UUID.fromString(jwt.getSubject()); // sub = 회원 UUID (AccessTokenIssuer)
        return CurrentUserResponse.from(authService.currentMember(memberId));
    }
}
