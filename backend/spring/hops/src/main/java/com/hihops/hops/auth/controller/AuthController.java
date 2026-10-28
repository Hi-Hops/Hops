package com.hihops.hops.auth.controller;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
        // Access는 본문(JSON)으로, Refresh는 Set-Cookie 헤더로 따로 내려준다. Refresh 원문은 JSON에 넣지 않는다.
        ResponseCookie refreshCookie = RefreshCookies.issue(refreshTokenService.issue(member), httpRequest.isSecure());
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
