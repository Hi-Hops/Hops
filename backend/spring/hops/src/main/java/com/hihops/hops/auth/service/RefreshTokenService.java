package com.hihops.hops.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.user.domain.Member;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
 *   <li>{@code auth:refresh-used:{토큰 해시}} → "세션ID|회원ID|세션 만료 시각|회전한 시각". 회전으로 이미 쓴 값.
 *       다시 들어오면 재사용(탈취)을 알아채고, 갱신 직후 예전 값으로 온 로그아웃도 세션을 찾을 수 있다.
 *       세션 만료 시각까지 남긴다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    /** 최초 로그인부터 절대 7일. 갱신해도 늘어나지 않는다 (WIRE_CONTRACT 10절). */
    public static final Duration LIFETIME = Duration.ofDays(7);

    private static final int TOKEN_BYTES = 32; // 256비트: 추측으로 맞힐 수 없는 길이
    private static final String SESSION_KEY = "auth:session:";
    private static final String REFRESH_KEY = "auth:refresh:";
    private static final String USED_KEY = "auth:refresh-used:";

    /**
     * 회전 뒤 이 시간 안에 예전 값이 다시 오면 탭 두 개의 동시 갱신 경합으로 본다: 그 요청만 401, 세션 유지 (Q-BE-2 팀 합의).
     * 이 시간이 지나서 오면 예전 값을 다른 사람이 갖고 있다고 보고 세션을 폐기한다.
     */
    static final Duration REUSE_GRACE = Duration.ofSeconds(10);

    /**
     * 회전의 "예전 값 소비"를 Redis 안에서 한 번에 실행한다: refresh 키를 refresh-used 키로 옮기고(RENAME은 남은 TTL 유지)
     * 회전 시각을 붙인다. 중간 상태(둘 다 없는 순간)가 없으므로, 갱신과 동시에 온 로그아웃도 둘 중 하나는 반드시 찾는다.
     * 반환: 옮기기 전 값. 키가 없으면 null.
     */
    private static final RedisScript<String> CONSUME = RedisScript.of("""
            local value = redis.call('GET', KEYS[1])
            if not value then
              return false
            end
            redis.call('RENAME', KEYS[1], KEYS[2])
            redis.call('SET', KEYS[2], value .. '|' .. ARGV[1], 'KEEPTTL')
            return value
            """, String.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    /** 로그인 성공 시: 새 로그인 세션을 열고 첫 Refresh를 발급한다. */
    public IssuedRefreshToken issue(Member member) {
        UUID sessionId = UUID.randomUUID();
        Instant expiresAt = clock.instant().plus(LIFETIME);
        redis.opsForValue().set(SESSION_KEY + sessionId, member.getId().toString(), LIFETIME);
        return store(new RefreshEntry(sessionId, member.getId(), expiresAt), LIFETIME);
    }

    /**
     * 갱신 1단계: 이 Refresh의 회원 ID. 읽기만 하고 소비하지 않는다.
     * 컨트롤러가 회원을 DB에서 확인한 뒤 {@link #rotate}를 부른다. DB가 일시 실패해도 쿠키의 Refresh로 다시 시도할 수 있다.
     */
    public UUID ownerOf(String refreshToken) {
        String tokenHash = hash(refreshToken);
        String stored = redis.opsForValue().get(REFRESH_KEY + tokenHash);
        if (stored == null) {
            revokeSessionIfReused(tokenHash);
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_REVOKED);
        }
        return RefreshEntry.parse(stored).memberId();
    }

    /**
     * 갱신 2단계: 받은 Refresh를 소비하고 같은 세션의 새 Refresh를 발급한다. 세션 만료 시각은 그대로 둔다.
     *
     * <p>소비는 {@link #CONSUME} 스크립트로 한 번에 한다. 같은 토큰으로 동시에 두 요청이 와도 값을 받는 쪽은 하나뿐이다.
     */
    public IssuedRefreshToken rotate(String refreshToken) {
        String tokenHash = hash(refreshToken);
        Instant now = clock.instant();
        String stored = redis.execute(CONSUME, List.of(REFRESH_KEY + tokenHash, USED_KEY + tokenHash),
                String.valueOf(now.toEpochMilli()));
        if (stored == null) {
            revokeSessionIfReused(tokenHash);
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_REVOKED);
        }
        RefreshEntry entry = RefreshEntry.parse(stored);
        // 재사용 검출로 세션이 폐기됐다면, 남아 있던 최신 Refresh라도 더는 받지 않는다.
        if (!Boolean.TRUE.equals(redis.hasKey(SESSION_KEY + entry.sessionId()))) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_REVOKED);
        }
        // 남은 시간만큼만 준다: 7일 기한을 늘리지 않는다.
        Duration remaining = Duration.between(now, entry.expiresAt());
        // 서버 시계와 Redis 만료가 어긋나 7일이 지난 값이 남아 있을 수 있다. 0 이하 TTL로 저장하면 만료 없이 남으므로 여기서 막는다.
        if (remaining.isNegative() || remaining.isZero()) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_REVOKED);
        }
        return store(entry, remaining);
    }

    /**
     * 로그아웃·재로그인·차단 회원의 갱신 시: 이 Refresh가 속한 로그인 세션을 폐기한다. 다른 기기의 로그인 세션은 그대로 둔다.
     *
     * <p>갱신 직후라 쿠키 값이 이미 회전으로 쓰였어도 "사용됨" 기록으로 세션을 찾아 끝낸다.
     * 그러지 않으면 갱신과 로그아웃이 겹쳤을 때 로그아웃이 204로 끝나도 세션이 살아남는다.
     *
     * @return 폐기한 세션의 회원 ID. 모르는 값이거나 이미 만료됐으면 비어 있다(로그아웃은 그래도 성공으로 응답한다).
     */
    public Optional<UUID> revoke(String refreshToken) {
        String tokenHash = hash(refreshToken);
        String stored = redis.opsForValue().getAndDelete(REFRESH_KEY + tokenHash);
        if (stored != null) {
            RefreshEntry entry = RefreshEntry.parse(stored);
            endSession(entry.sessionId(), entry.memberId());
            return Optional.of(entry.memberId());
        }
        String used = redis.opsForValue().get(USED_KEY + tokenHash);
        if (used == null) {
            return Optional.empty();
        }
        UsedEntry entry = UsedEntry.parse(used);
        endSession(entry.sessionId(), entry.memberId());
        return Optional.of(entry.memberId());
    }

    // 세션 키를 지우면 그 세션의 남은 Refresh가 모두 무효가 된다(rotate가 세션 키를 확인). 열린 SSE 정리는 이벤트를 받는 쪽이 한다.
    private void endSession(UUID sessionId, UUID memberId) {
        redis.delete(SESSION_KEY + sessionId);
        events.publishEvent(new LoginSessionRevokedEvent(memberId));
    }

    // 이미 회전으로 쓴 값이 유예 시간 뒤에 다시 왔다 = 누군가 예전 값을 갖고 있다. 그 로그인 세션 전체를 끊는다.
    private void revokeSessionIfReused(String tokenHash) {
        String used = redis.opsForValue().get(USED_KEY + tokenHash);
        if (used == null) {
            return; // 처음 보는 값이거나 이미 만료됨
        }
        UsedEntry entry = UsedEntry.parse(used);
        if (!clock.instant().isAfter(entry.rotatedAt().plus(REUSE_GRACE))) {
            log.info("Refresh 동시 갱신 경합: 늦은 요청만 거절 sessionId={}", entry.sessionId());
            return;
        }
        endSession(entry.sessionId(), entry.memberId());
        log.warn("Refresh 재사용 감지: 로그인 세션 폐기 sessionId={} memberId={}", entry.sessionId(), entry.memberId());
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

    /** 회전으로 이미 쓴 Refresh의 기록. 값은 {@link #CONSUME}이 RefreshEntry 뒤에 회전 시각(밀리초)을 붙여 만든다. */
    private record UsedEntry(UUID sessionId, UUID memberId, Instant rotatedAt) {

        static UsedEntry parse(String value) {
            String[] parts = value.split("\\|");
            return new UsedEntry(UUID.fromString(parts[0]), UUID.fromString(parts[1]),
                    Instant.ofEpochMilli(Long.parseLong(parts[3])));
        }
    }

    /** 발급한 Refresh 원문과 남은 수명. 원문이 로그에 찍히지 않도록 toString에서 숨긴다. */
    public record IssuedRefreshToken(String value, Duration maxAge) {

        @Override
        public String toString() {
            return "IssuedRefreshToken[maxAge=" + maxAge + "]";
        }
    }
}
