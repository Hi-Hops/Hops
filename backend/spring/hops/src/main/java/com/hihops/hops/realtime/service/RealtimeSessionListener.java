package com.hihops.hops.realtime.service;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.hihops.hops.auth.service.LoginSessionRevokedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 로그인 세션이 끝나면(로그아웃·재로그인·Refresh 재사용 감지·차단 회원) 그 회원의 열린 SSE를 닫는다 (WIRE_CONTRACT 10절).
 *
 * <p>회원 단위로 닫는다. Access 토큰에 세션 ID가 없어 어느 기기의 연결인지 구분할 수 없기 때문이다.
 * 다른 기기의 연결은 유효한 Access로 다시 연결하면 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RealtimeSessionListener {

    private final RealtimeService realtimeService;

    // 이벤트는 세션을 끝낸 요청과 같은 스레드에서 바로 실행된다. 여기서 예외가 새면 그 요청(재사용 감지 refresh·로그인·로그아웃)이
    // 500이 된다. 세션 종료(Redis)는 이미 끝났고 남은 연결도 전송 전 권한 재검사로 막히므로, SSE 정리 실패는 로그로만 남긴다.
    @EventListener
    public void onSessionRevoked(LoginSessionRevokedEvent event) {
        try {
            realtimeService.disconnect(event.memberId());
        } catch (RuntimeException exception) {
            log.error("세션 종료 후 SSE 정리 실패 memberId={}", event.memberId(), exception);
        }
    }
}
