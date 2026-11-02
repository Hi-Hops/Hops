package com.hihops.hops.realtime.service;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.hihops.hops.auth.service.LoginSessionRevokedEvent;

import lombok.RequiredArgsConstructor;

/**
 * 로그인 세션이 끝나면(로그아웃·재로그인·Refresh 재사용 감지·차단 회원) 그 회원의 열린 SSE를 닫는다 (WIRE_CONTRACT 10절).
 *
 * <p>회원 단위로 닫는다. Access 토큰에 세션 ID가 없어 어느 기기의 연결인지 구분할 수 없기 때문이다.
 * 다른 기기의 연결은 유효한 Access로 다시 연결하면 된다.
 */
@Component
@RequiredArgsConstructor
public class RealtimeSessionListener {

    private final RealtimeService realtimeService;

    @EventListener
    public void onSessionRevoked(LoginSessionRevokedEvent event) {
        realtimeService.disconnect(event.memberId());
    }
}
