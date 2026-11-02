package com.hihops.hops.realtime.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.hihops.hops.auth.service.LoginSessionRevokedEvent;

class RealtimeSessionListenerTest {

    @Test
    void SSE_정리가_실패해도_세션_종료를_알린_쪽으로_예외를_넘기지_않는다() {
        // 이벤트는 같은 스레드에서 바로 실행된다. 여기서 예외가 새면 재사용 감지 refresh가 401 대신 500이 되고,
        // 로그인·로그아웃도 실패한다. 세션 종료(Redis)는 이미 끝났으므로 SSE 정리 실패는 로그로만 남긴다.
        RealtimeService realtime = mock(RealtimeService.class);
        doThrow(new IllegalStateException("emitter 오류")).when(realtime).disconnect(any());

        assertThatCode(() -> new RealtimeSessionListener(realtime)
                .onSessionRevoked(new LoginSessionRevokedEvent(UUID.randomUUID())))
                .doesNotThrowAnyException();
    }
}
