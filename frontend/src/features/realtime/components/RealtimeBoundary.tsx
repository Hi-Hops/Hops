import { useEffect } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { useSession } from '@/features/auth';
import { realtime } from '@/shared/realtime/mockTransport';
import { MockHttpError } from '@/shared/api/mockBackend';
import { Button } from '@/shared/ui/Button';
import { useConnection } from '../model/connectionStore';
import styles from './RealtimeBoundary.module.css';

export function RealtimeBoundary({ children }: { children: ReactNode }) {
  const token = useSession((session) => session.token);
  const state = useConnection((connection) => connection.state);
  const client = useQueryClient();
  useEffect(() => {
    const offConnection = realtime.onConnection((next) => {
      useConnection.getState().setState(next);
      if (next === 'connected') void client.invalidateQueries();
    });
    const offState = realtime.on('state.changed', () => {
      void client.invalidateQueries();
    });
    const offMessage = realtime.on('message.stored', () => {
      void client.invalidateQueries();
    });
    try {
      if (token) realtime.connect(token);
      else realtime.disconnect();
    } catch (error) {
      if (error instanceof MockHttpError && error.status === 401) useSession.getState().expire();
    }
    return () => {
      offConnection();
      offState();
      offMessage();
      realtime.disconnect();
    };
  }, [client, token]);
  function reconnect() {
    try {
      if (token) realtime.connect(token);
    } catch {
      useSession.getState().expire();
    }
  }
  return (
    <>
      {state !== 'connected' && (
        <div className={styles.banner} role="status">
          <span>
            {state === 'relayError'
              ? '채팅 서버와 업무 서버의 연결이 끊겼어요.'
              : '실시간 연결이 끊겼어요. 입력과 저장된 결과는 유지돼요.'}
          </span>
          <Button variant="secondary" onClick={reconnect}>
            다시 연결
          </Button>
        </div>
      )}
      {children}
    </>
  );
}
