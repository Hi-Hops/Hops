import { useEffect } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { useSession } from '@/features/auth';
import { realtime } from '@/shared/realtime';
import type { SubscriptionTarget } from '@/shared/api/types';
import { Button } from '@/shared/ui/Button';
import { useConnection } from '../model/connectionStore';
import styles from './RealtimeBoundary.module.css';

export function RealtimeBoundary({ children }: { children: ReactNode }) {
  const token = useSession((session) => session.token);
  const state = useConnection((connection) => connection.state);
  const client = useQueryClient();
  useEffect(() => {
    let active = true;
    const queues = new Map<string, { dirty: boolean; flight: Promise<void> | null }>();
    // 조회 중 도착한 변경은 취소 경쟁 대신 다음 조회로 이어서 반영한다.
    function synchronize(target: SubscriptionTarget) {
      const key = `${target.type}:${target.id}`;
      const queue = queues.get(key) ?? { dirty: false, flight: null };
      queues.set(key, queue);
      queue.dirty = true;
      const needsAnother = () => active && queue.dirty;
      queue.flight ??= (async () => {
        do {
          queue.dirty = false;
          await client.invalidateQueries(
            {
              predicate: (query) => {
                const parts = query.queryKey;
                if (target.type === 'INQUIRY')
                  return (
                    parts[0] === 'inquiries' ||
                    (parts[0] === 'chat' && (parts[1] === target.id || parts[1] === 'run'))
                  );
                if (target.type === 'DEPARTMENT_TICKETS')
                  return parts[0] === 'tickets' && parts[1] === 'list';
                return parts[0] === 'tickets' && parts[1] === 'detail' && parts[3] === target.id;
              },
            },
            { throwOnError: true, cancelRefetch: false },
          );
        } while (needsAnother());
      })().finally(() => {
        queue.flight = null;
      });
      return queue.flight;
    }
    const offConnection = realtime.onConnection((next) => {
      useConnection.getState().setState(next);
    });
    const offState = realtime.on('state.changed', (event) => {
      // 조회 오류는 각 Query 화면이 표시한다. SSE 연결 상태와 혼동하지 않는다.
      void synchronize(event.target).catch(() => undefined);
    });
    const offMessage = realtime.on('message.stored', (event) => {
      void synchronize({ type: 'INQUIRY', id: event.inquiryId }).catch(() => undefined);
    });
    const offSync = realtime.onSync((target) => synchronize(target));
    try {
      if (token) realtime.connect(token);
      else realtime.disconnect();
    } catch (error) {
      if (error instanceof Error && 'status' in error && error.status === 401)
        useSession.getState().expire();
      else useConnection.getState().setState('disconnected');
    }
    return () => {
      active = false;
      offConnection();
      offState();
      offMessage();
      offSync();
    };
  }, [client, token]);
  useEffect(
    () => () => {
      realtime.disconnect();
    },
    [],
  );
  useEffect(() => {
    function online() {
      if (token && realtime.getState() !== 'connected') realtime.connect(token);
    }
    window.addEventListener('online', online);
    return () => {
      window.removeEventListener('online', online);
    };
  }, [token]);
  function reconnect() {
    try {
      if (token) realtime.connect(token);
    } catch {
      useConnection.getState().setState('disconnected');
    }
  }
  return (
    <>
      {state !== 'connected' && state !== 'idle' && (
        <div className={styles.banner} role="status">
          <span>
            {state === 'connecting'
              ? '실시간 상태를 연결하고 있어요.'
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
