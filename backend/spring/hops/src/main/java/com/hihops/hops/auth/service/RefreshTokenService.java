package com.hihops.hops.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.user.domain.Member;

import lombok.RequiredArgsConstructor;

/**
 * Refresh Token(Access 재발급 교환권)을 만들고, Redis에 보관하고, 갱신 때마다 새 것으로 바꾼다(회전).
 *
 * <p>Access와 달리 JWT가 아니라 아무 뜻 없는 무작위 문자열이다. 내용을 담을 필요가 없고,
 * 서버가 보관한 값과 맞는지만 확인하면 되기 때문이다(복권 번호처럼 맞히기만 어려우면 된다).
 *
 * <p>Redis 키 (docs/DATA.md 5절)
 * <ul>
 *   <li>{@code auth:session:{sessionId}} → 회원 ID. 로그인 1번 = 세션 1개. 최초 로그인 + 7일 뒤 자동 삭제.</li>
 *   <li>{@code auth:refresh:{토큰 해시}} → "세션ID|회원ID|세션 만료 시각". 원문 대신 해시를 키로 써서,
 *       Redis 내용이 새어 나가도 쿠키 값을 알아낼 수 없다(비밀번호를 해시로 저장하는 것과 같은 이유).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    /** 최초 로그인부터 절대 7일. 갱신해도 늘어나지 않는다 (WIRE_CONTRACT 10절). */
    public static final Duration LIFETIME = Duration.ofDays(7);

    private static final int TOKEN_BYTES = 32; // 256비트: 추측으로 맞힐 수 없는 길이
    private static final String SESSION_KEY = "auth:session:";
    private static final String REFRESH_KEY = "auth:refresh:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;

    /** 로그인 성공 시: 새 로그인 세션을 열고 첫 Refresh를 발급한다. */
    public IssuedRefreshToken issue(Member member) {
        UUID sessionId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(LIFETIME);
        redis.opsForValue().set(SESSION_KEY + sessionId, member.getId().toString(), LIFETIME);
        return store(new RefreshEntry(sessionId, member.getId(), expiresAt), LIFETIME);
    }

    /**
     * 갱신 시: 받은 Refresh를 지우고 같은 세션의 새 Refresh를 발급한다. 세션 만료 시각은 그대로 둔다.
     *
     * <p>GETDEL은 "읽기와 지우기"를 한 번에 한다. 같은 토큰으로 동시에 두 요청이 와도 값을 받는 쪽은 하나뿐이다.
     */
    public Rotated rotate(String refreshToken) {
        String stored = redis.opsForValue().getAndDelete(REFRESH_KEY + hash(refreshToken));
        if (stored == null) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_REVOKED);
        }
        RefreshEntry entry = RefreshEntry.parse(stored);
        // 남은 시간만큼만 준다: 7일 기한을 늘리지 않는다.
        return new Rotated(entry.memberId(), store(entry, Duration.between(Instant.now(), entry.expiresAt())));
    }

    private IssuedRefreshToken store(RefreshEntry entry, Duration ttl) {
        String value = newTokenValue();
        redis.opsForValue().set(REFRESH_KEY + hash(value), entry.format(), ttl);
        return new IssuedRefreshToken(value, ttl);
    }

    private static String newTokenValue() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        // URL-safe Base64: 쿠키 값에 넣어도 깨지는 문자(+, /, =)가 없다.
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256은 모든 Java에 있다", e);
        }
    }

    /** Redis에 저장하는 Refresh 한 개의 정보. 회전해도 sessionId·expiresAt은 그대로 이어진다. */
    private record RefreshEntry(UUID sessionId, UUID memberId, Instant expiresAt) {

        String format() {
            return sessionId + "|" + memberId + "|" + expiresAt.getEpochSecond();
        }

        static RefreshEntry parse(String value) {
            String[] parts = value.split("\\|");
            return new RefreshEntry(UUID.fromString(parts[0]), UUID.fromString(parts[1]),
                    Instant.ofEpochSecond(Long.parseLong(parts[2])));
        }
    }

    /** 회전 결과: 누구의 토큰이었는지와 새로 발급한 Refresh. */
    public record Rotated(UUID memberId, IssuedRefreshToken token) {
    }

    /** 발급한 Refresh 원문과 남은 수명. 원문이 로그에 찍히지 않도록 toString에서 숨긴다. */
    public record IssuedRefreshToken(String value, Duration maxAge) {

        @Override
        public String toString() {
            return "IssuedRefreshToken[maxAge=" + maxAge + "]";
        }
    }
}
