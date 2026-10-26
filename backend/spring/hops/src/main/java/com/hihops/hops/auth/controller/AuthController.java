package com.hihops.hops.auth.controller;

import java.util.UUID;

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
import com.hihops.hops.user.domain.Member;
import com.hihops.hops.user.dto.CurrentUserResponse;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AccessTokenIssuer accessTokenIssuer;

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        Member member = authService.login(request.loginId(), request.password()); // 틀리면 여기서 401
        return LoginResponse.of(accessTokenIssuer.issue(member), member);
    }

    /** 토큰 주인의 현재 사용자 정보. 토큰이 없거나 틀리면 이 메서드까지 오지 않고 401이 된다(SecurityConfig). */
    @GetMapping("/me")
    public CurrentUserResponse me(@AuthenticationPrincipal Jwt jwt) {
        UUID memberId = UUID.fromString(jwt.getSubject()); // sub = 회원 UUID (AccessTokenIssuer)
        return CurrentUserResponse.from(authService.currentMember(memberId));
    }
}
