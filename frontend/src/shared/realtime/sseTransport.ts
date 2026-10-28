import {
  ApiError,
  currentAccessToken,
  recoverAuthorization,
  reportUnauthorized,
} from '@/shared/api/client';
import { isApiError, isRecord, localError } from '@/shared/api/wire';
import { liveConnection } from '@/shared/config/connection';
import type { SubscriptionTarget } from '@/shared/api/types';
import { TransportEvents } from './transportEvents';
import { readSse } from './sseParser';
import { decodeEvent } from './sseEvents';
import type { RealtimeTransport, TargetState } from './types';

type Entry = {
  target: SubscriptionTarget;
  listeners: Set<(state: TargetState) => void>;
  state: TargetState;
  cursor: string;
  seen: Set<string>;
  controller: AbortController | null;
};
const keyOf = (target: SubscriptionTarget) => `${target.type}:${target.id}`;
function pause(ms: number, signal: AbortSignal) {
  return new Promise<void>((resolve) => {
    const finish = () => {
      clearTimeout(timer);
      signal.removeEventListener('abort', finish);
      resolve();
    };
    const timer = setTimeout(finish, ms);
    signal.addEventListener('abort', finish, { once: true });
    if (signal.aborted) finish();
  });
}

export class SseTransport extends TransportEvents implements RealtimeTransport {
  private token: string | null = null;
  private entries = new Map<string, Entry>();
  constructor(
    private connectTimeout = 5000,
    private reconnectWindow = 30_000,
    private baseDelay = 1000,
  ) {
    super();
  }
  private current(entry: Entry, controller: AbortController) {
    return (
      entry.controller === controller &&
      !controller.signal.aborted &&
      this.entries.get(keyOf(entry.target)) === entry
    );
  }
  private update(entry: Entry, state: TargetState) {
    entry.state = state;
    for (const listener of entry.listeners) listener(state);
    const entries = [...this.entries.values()];
    this.setState(
      !entries.length
        ? 'idle'
        : entries.some((e) => e.state === 'connecting')
          ? 'connecting'
          : entries.some((e) => ['disconnected', 'denied'].includes(e.state))
            ? 'disconnected'
            : 'connected',
    );
  }
  connect(token: string) {
    this.token = token;
    for (const entry of this.entries.values()) this.start(entry);
    if (!this.entries.size) this.setState('idle');
  }
  disconnect() {
    this.token = null;
    for (const entry of this.entries.values()) {
      entry.controller?.abort();
      entry.cursor = '';
      entry.seen.clear();
      for (const listener of entry.listeners) listener('disconnected');
    }
    this.entries.clear();
    this.setState('idle');
  }
  subscribe(target: SubscriptionTarget, listener?: (state: TargetState) => void) {
    const key = keyOf(target);
    const entry = this.entries.get(key) ?? {
      target,
      listeners: new Set<(state: TargetState) => void>(),
      state: 'idle' as const,
      cursor: '',
      seen: new Set<string>(),
      controller: null,
    };
    const notify = (state: TargetState) => {
      listener?.(state);
    };
    entry.listeners.add(notify);
    this.entries.set(key, entry);
    notify(entry.state);
    if (this.token && !entry.controller) this.start(entry);
    return () => {
      entry.listeners.delete(notify);
      if (!entry.listeners.size) {
        entry.controller?.abort();
        if (this.entries.get(key) === entry) this.entries.delete(key);
        this.update(entry, 'idle');
      }
    };
  }
  private start(entry: Entry) {
    entry.controller?.abort();
    const controller = new AbortController();
    entry.controller = controller;
    void this.run(entry, controller).catch(() => {
      if (this.current(entry, controller)) this.update(entry, 'disconnected');
    });
  }
  private async run(entry: Entry, controller: AbortController) {
    let failedAt: number | null = null;
    let attempt = 0;
    while (this.current(entry, controller) && this.token) {
      const token = this.token;
      const request = new AbortController();
      const abort = () => {
        request.abort();
      };
      controller.signal.addEventListener('abort', abort, { once: true });
      const timer = setTimeout(abort, this.connectTimeout);
      const sentCursor = entry.cursor;
      const outcome = { received: false, resync: false };

      let retryAfter = 0;
      let reading: Promise<void> | null = null;
      const openedAt = Date.now();
      try {
        this.update(entry, 'connecting');
        const query = new URLSearchParams({
          targetType: entry.target.type,
          targetId: entry.target.id,
        });
        const response = await fetch(
          `${liveConnection().apiBaseUrl}/api/realtime/events?${query.toString()}`,
          {
            headers: {
              Accept: 'text/event-stream',
              Authorization: `Bearer ${token}`,
              ...(sentCursor ? { 'Last-Event-ID': sentCursor } : {}),
            },
            credentials: 'omit',
            signal: request.signal,
          },
        );
        clearTimeout(timer);
        if (!this.current(entry, controller)) {
          await response.body?.cancel();
          return;
        }
        if (!response.ok) {
          // 오류 JSON을 읽는 동안에도 취소·timeout을 적용한다.
          const errorTimer = setTimeout(abort, this.connectTimeout);
          let body: unknown;
          try {
            body = await response.json();
          } finally {
            clearTimeout(errorTimer);
          }
          const seconds = Number(response.headers.get('Retry-After'));
          if (response.status === 429 && Number.isFinite(seconds) && seconds > 0)
            retryAfter = seconds * 1000;
          throw new ApiError(
            response.status,
            isApiError(body)
              ? body
              : localError('INVALID_RESPONSE', '실시간 연결 오류 응답을 확인해 주세요.'),
          );
        }
        if (
          !response.headers.get('Content-Type')?.includes('text/event-stream') ||
          !response.body
        ) {
          await response.body?.cancel();
          throw new Error('SSE 응답 형식이 달라요.');
        }
        this.update(entry, 'connected');
        reading = readSse(
          response.body,
          (message) => {
            if (!this.current(entry, controller) || request.signal.aborted) return;
            if (message.event === 'resync.required') {
              const value: unknown = JSON.parse(message.data);
              if (
                !isRecord(value) ||
                typeof value.eventId !== 'string' ||
                !value.eventId ||
                value.eventId !== message.id
              )
                throw new Error('재동기화 이벤트 식별자가 일치하지 않아요.');
              outcome.resync = true;
              this.emit('resync.required', { eventId: value.eventId });
              request.abort();
              return;
            }
            let input: unknown;
            try {
              input = JSON.parse(message.data);
            } catch {
              throw new Error('SSE JSON이 올바르지 않아요.');
            }
            const event = decodeEvent(message.event, input, entry.target);
            if (!event) throw new Error('SSE 이벤트 필드 또는 대상이 달라요.');
            if (message.id && message.id !== event.value.eventId)
              throw new Error('SSE 이벤트 식별자가 일치하지 않아요.');
            outcome.received = true;
            failedAt = null;
            attempt = 0;
            if (event.name === 'auth.invalidated') {
              this.emit(event.name, event.value);
              throw new ApiError(401, { ...localError(event.value.code, event.value.message) });
            }
            if (!message.id) throw new Error('업무 이벤트 ID가 없어요.');
            if (!entry.seen.has(event.value.eventId)) {
              this.emit(event.name, event.value);
              entry.seen.add(event.value.eventId);
              if (entry.seen.size > 2048) {
                const first = entry.seen.values().next().value;
                if (first) entry.seen.delete(first);
              }
            }
            entry.cursor = message.id;
          },
          request.signal,
        );
        // 첫 연결/재연결 모두 스트림을 읽기 시작한 뒤 REST로 확정 상태를 맞춘다.
        // 조회 실패는 조회 화면에서 안내한다. 정상 스트림까지 끊어 재시도하지 않는다.
        // resync.required 이후의 필수 REST 복구 실패는 아래 복구 분기에서 별도로 처리한다.
        await Promise.all([reading, this.synchronize(entry.target).catch(() => undefined)]);
        if (!this.current(entry, controller)) return;
        if (Date.now() - openedAt > this.connectTimeout) {
          failedAt = null;
          attempt = 0;
        }
      } catch (error) {
        if (!this.current(entry, controller)) return;
        if (error instanceof ApiError) {
          if (
            ['AUTH_CONTEXT_CHANGED', 'REFRESH_TOKEN_REVOKED', 'REFRESH_TOKEN_EXPIRED'].includes(
              error.body.code,
            )
          ) {
            reportUnauthorized(token);
            this.update(entry, 'denied');
            return;
          }
          if (error.status === 401) {
            try {
              const recovered = await recoverAuthorization(token);
              const fresh = currentAccessToken();
              if (this.current(entry, controller) && recovered && fresh && fresh !== token) {
                this.connect(fresh);
                return;
              }
            } catch {
              /* 네트워크 장애는 인증 만료가 아니다. */
            }
            if (this.current(entry, controller)) this.update(entry, 'disconnected');
            return;
          }
          if ([400, 403, 404].includes(error.status)) {
            this.update(entry, 'denied');
            return;
          }
        }
      } finally {
        clearTimeout(timer);
        request.abort();
        if (reading) await reading.catch(() => undefined);
        controller.signal.removeEventListener('abort', abort);
      }
      if (!this.current(entry, controller)) return;
      // 제어 이벤트와 알림 유실에 따른 EOF 모두 REST 복구 후 커서를 초기화한다.
      if (outcome.resync || (sentCursor && !outcome.received)) {
        entry.cursor = '';
        entry.seen.clear();
        this.emit('stream.reset', { target: entry.target });
        try {
          await this.synchronize(entry.target);
        } catch {
          this.update(entry, 'disconnected');
          return;
        }
        if (!this.current(entry, controller)) return;
      }
      failedAt ??= Date.now();
      const remaining = this.reconnectWindow - (Date.now() - failedAt);
      const delay = Math.max(
        retryAfter,
        Math.min(5000, this.baseDelay * 2 ** Math.min(attempt++, 3) * (0.8 + Math.random() * 0.4)),
      );
      if (remaining <= 0 || delay >= remaining) {
        this.update(entry, 'disconnected');
        return;
      }
      this.update(entry, 'connecting');
      await pause(delay, controller.signal);
    }
  }
}
