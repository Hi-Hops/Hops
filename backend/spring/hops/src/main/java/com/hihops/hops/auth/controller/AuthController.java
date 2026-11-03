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
import com.hihops.hops.auth.service.RefreshCookieProperties;
import com.hihops.hops.auth.service.RefreshCookies;
import com.hihops.hops.auth.service.RefreshTokenService;
import com.hihops.hops.auth.service.RefreshTokenService.IssuedRefreshToken;
import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.global.exception.ErrorResponse;
import com.hihops.hops.global.filter.RequestIdFilter;
import com.hihops.hops.user.domain.Member;
import com.hihops.hops.user.dto.CurrentUserResponse;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/auth") // 실제 주소는 /api/v2/auth (ApiPathConfig)
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AccessTokenIssuer accessTokenIssuer;
    private final RefreshTokenService refreshTokenService;
    private final RefreshCookieProperties cookieProperties;

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request,
            @CookieValue(name = RefreshCookies.NAME, required = false) String previousRefresh) {
        Member member = authService.login(request.loginId(), request.password()); // 틀리면 여기서 401
        // 같은 브라우저의 재로그인·계정 전환: 쿠키만 덮어쓰면 예전 세션이 7일 동안 남는다. 성공한 뒤에만 정리한다.
        // 정리는 덤이라 실패해도 로그인은 진행한다(손상된 예전 쿠키 때문에 로그인이 막히면 프론트는 그 쿠키를 지울 수 없다).
        if (hasValue(previousRefresh)) {
            tryRevoke(previousRefresh, "재로그인 중 예전 세션 정리 실패");
        }
        return withTokens(member, refreshTokenService.issue(member));
    }

    /**
     * Access가 만료되면 프론트가 부른다. 본문 없이 Refresh 쿠키만 받는다 (WIRE_CONTRACT 2절).
     * 쿠키가 없거나 무효면 401 REFRESH_TOKEN_REVOKED → 프론트는 재로그인한다.
     */
    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(
            @CookieValue(name = RefreshCookies.NAME, required = false) String refreshToken) {
        if (!hasValue(refreshToken)) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_REVOKED);
        }
        // 회원 확인(DB)을 먼저 하고 회전은 마지막에 한다. DB가 일시 실패하면 Refresh를 소비하지 않아 같은 쿠키로 다시 시도할 수 있다.
        // 토큰이 아니라 DB를 다시 읽으므로, 그 사이 바뀐 부서·역할로 새 Access를 만든다.
        Member member = currentMemberOrEndSession(refreshTokenService.ownerOf(refreshToken), refreshToken);
        return withTokens(member, refreshTokenService.rotate(refreshToken));
    }

    /**
     * 현재 로그인 세션을 끝낸다 (WIRE_CONTRACT 12.2). 쿠키가 없거나 이미 끝난 세션이어도 204와 쿠키 삭제로 응답한다:
     * 프론트는 결과와 관계없이 화면 정리를 하고, 반복 호출도 같은 결과가 된다.
     *
     * <p>서버 세션을 끝내지 못하면(저장소 장애 등) 503으로 알리되 쿠키 삭제는 그대로 보낸다.
     * 프론트는 HttpOnly 쿠키를 지울 수 없어서, 여기서 지우지 않으면 장애 뒤 그 쿠키로 세션이 되살아난다.
     */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(
            @CookieValue(name = RefreshCookies.NAME, required = false) String refreshToken) {
        String expiredCookie = RefreshCookies.expire(cookieProperties.secure()).toString();
        // 세션을 끝내면 그 회원의 열린 SSE도 닫힌다(LoginSessionRevokedEvent). 남은 Access(최대 15분)는 프론트가 메모리에서 버린다.
        if (hasValue(refreshToken) && !tryRevoke(refreshToken, "로그아웃 중 세션 정리 실패: 쿠키만 삭제")) {
            return ResponseEntity.status(ErrorCode.SERVICE_UNAVAILABLE.status())
                    .header(HttpHeaders.SET_COOKIE, expiredCookie)
                    .body(ErrorResponse.of(ErrorCode.SERVICE_UNAVAILABLE, RequestIdFilter.currentRequestId()));
        }
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, expiredCookie).build();
    }

    // 차단·삭제된 회원이면 그 세션을 끝내고 재로그인 코드로 거절한다. DB 장애 같은 다른 예외는 세션을 건드리지 않는다.
    private Member currentMemberOrEndSession(UUID memberId, String refreshToken) {
        try {
            return authService.currentMember(memberId);
        } catch (BusinessException exception) {
            tryRevoke(refreshToken, "차단 회원의 세션 정리 실패");
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_REVOKED);
        }
    }

    // 세션 정리를 시도하고 성공 여부를 돌려준다. 저장소 장애·손상된 값 등 어떤 실패도 호출한 API의 응답을 깨뜨리지 않게 로그로 남긴다.
    private boolean tryRevoke(String refreshToken, String failureLog) {
        try {
            refreshTokenService.revoke(refreshToken);
            return true;
        } catch (RuntimeException exception) {
            log.error(failureLog, exception);
            return false;
        }
    }

    private static boolean hasValue(String cookie) {
        return cookie != null && !cookie.isBlank();
    }

    // Access는 본문(JSON)으로, Refresh는 Set-Cookie 헤더로 따로 내려준다. Refresh 원문은 JSON에 넣지 않는다.
    private ResponseEntity<LoginResponse> withTokens(Member member, IssuedRefreshToken refreshToken) {
        ResponseCookie refreshCookie = RefreshCookies.issue(refreshToken, cookieProperties.secure());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .body(LoginResponse.of(accessTokenIssuer.issue(member), authService.currentUser(member)));
    }

    /** 토큰 주인의 현재 사용자 정보. 토큰이 없거나 틀리면 이 메서드까지 오지 않고 401이 된다(SecurityConfig). */
    @GetMapping("/me")
    public CurrentUserResponse me(@AuthenticationPrincipal Jwt jwt) {
        UUID memberId = UUID.fromString(jwt.getSubject()); // sub = 회원 UUID (AccessTokenIssuer)
        return authService.currentUser(authService.currentMember(memberId));
    }
}
