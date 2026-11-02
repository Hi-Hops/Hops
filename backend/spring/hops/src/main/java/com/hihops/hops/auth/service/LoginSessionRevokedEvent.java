package com.hihops.hops.auth.service;

import java.util.UUID;

/**
 * 로그인 세션이 끝났다는 알림 (로그아웃·재로그인·Refresh 재사용 감지·차단 회원의 갱신).
 * 인증 쪽은 "누구의 세션이 끝났는지"만 알리고, 열린 SSE 닫기 같은 후속 처리는 받는 쪽이 한다
 * (realtime.service.RealtimeSessionListener). 인증 코드가 실시간 기능을 직접 알 필요가 없다.
 */
public record LoginSessionRevokedEvent(UUID memberId) {
}
