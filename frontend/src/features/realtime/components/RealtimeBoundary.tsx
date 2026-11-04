import { useEffect } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { useSession } from '@/features/auth';
import { realtime } from '@/shared/realtime';
import type { SubscriptionTarget } from '@/shared/api/types';
import { useConnection } from '../model/connectionStore';

export function RealtimeBoundary({ children }: { children: ReactNode }) {
  const token = useSession((session) => session.token);
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
                if (parts[0] === 'hops-system-1') {
                  if (target.type === 'INQUIRY')
                    return parts[1] === 'chat' && parts[3] === target.id;
                  return [
                    'tickets',
                    'related',
                    'cases',
                    'generation',
                    'resolution',
                    'case-source',
                  ].includes(String(parts[1]));
                }
                if (target.type === 'INQUIRY') return parts[0] === 'inquiries';
                return false;
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
    const offAuth = realtime.on('auth.invalidated', (event) => {
      if (event.code !== 'ACCESS_TOKEN_EXPIRED') {
        client.clear();
        useSession.getState().expire();
      }
    });
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
      offAuth();
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
  // 연결 상태는 헤더의 ConnectionIndicator가 표시한다.
  return <>{children}</>;
}
