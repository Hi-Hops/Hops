package com.hihops.hops.auth.dto;

import java.time.Instant;

import com.hihops.hops.auth.service.AccessTokenIssuer.IssuedToken;
import com.hihops.hops.user.domain.Member;
import com.hihops.hops.user.dto.CurrentUserResponse;

/**
 * 로그인 성공 응답 (specs/contracts/DATA_TYPES.md LoginResponse).
 *
 * <pre>
 * { "accessToken": "eyJ...", "expiresAt": "2026-10-26T07:15:00Z", "user": { "id": "...", "employeeNo": "20260001", ... } }
 * </pre>
 *
 * <p>프론트는 accessToken을 메모리에만 두고 요청마다 {@code Authorization: Bearer <토큰>}으로 보낸다.
 */
public record LoginResponse(String accessToken, Instant expiresAt, CurrentUserResponse user) {

    public static LoginResponse of(IssuedToken token, Member member) {
        return new LoginResponse(token.value(), token.expiresAt(), CurrentUserResponse.from(member));
    }

    // 토큰 원문이 로그에 남지 않도록 사용자 정보만 보여준다.
    @Override
    public String toString() {
        return "LoginResponse[expiresAt=" + expiresAt + ", user=" + user + "]";
    }
}
