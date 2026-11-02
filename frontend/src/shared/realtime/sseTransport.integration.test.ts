// @vitest-environment node
import { createServer } from 'node:http';
import type { IncomingMessage, ServerResponse } from 'node:http';
import type { AddressInfo } from 'node:net';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  registerTokenProvider,
  registerUnauthorizedHandler,
  registerRefreshHandler,
} from '@/shared/api/client';
import { SseTransport } from './sseTransport';

const target = { type: 'DEPARTMENT_TICKETS' as const, id: '00000000-0000-4000-8000-000000000001' };
const value = (eventId = crypto.randomUUID()) => ({
  eventId,
  target: { type: target.type, id: target.id },
  inquiryVersion: null,
});
let peers: ServerResponse[];
let requests: IncomingMessage[];
let handle: (req: IncomingMessage, res: ServerResponse) => void;
let token: string;
let transport: SseTransport;
const server = createServer((req, res) => {
  requests.push(req);
  handle(req, res);
});
function send(res: ServerResponse, event: string, payload: unknown, id?: string) {
  res.write((id ? `id: ${id}\n` : '') + `event: ${event}\ndata: ${JSON.stringify(payload)}\n\n`);
}
beforeEach(async () => {
  peers = [];
  requests = [];
  token = 'test-access';
  registerTokenProvider(() => token);
  registerUnauthorizedHandler(() => {
    token = '';
  });
  registerRefreshHandler(() => Promise.resolve(false));
  handle = (_req, res) => {
    res.writeHead(200, { 'Content-Type': 'text/event-stream' });
    res.write(': connected\n\n');
    peers.push(res);
  };
  await new Promise<void>((resolve) => {
    server.listen(0, '127.0.0.1', resolve);
  });
  const address = server.address() as AddressInfo;
  vi.stubEnv('VITE_API_BASE_URL', `http://127.0.0.1:${address.port}`);
  transport = new SseTransport(100, 300, 10);
});
afterEach(async () => {
  transport.disconnect();
  for (const res of peers) res.end();
  server.closeAllConnections();
  await new Promise<void>((resolve, reject) => {
    server.close((error) => {
      if (error) reject(error);
      else resolve();
    });
  });
  vi.unstubAllEnvs();
});

describe('F002 fetch SSE 실제 HTTP 대역', () => {
  it('F002-AC01 초기 REST 조회가 실패해도 정상 SSE에서 이벤트를 계속 수신한다', async () => {
    const sync = vi.fn(() => Promise.reject(new Error('조회 API 준비 중')));
    const received = vi.fn();
    transport.onSync(sync);
    transport.on('state.changed', received);
    transport.connect(token);
    transport.subscribe(target);
    await vi.waitFor(() => {
      expect(sync).toHaveBeenCalledOnce();
    });
    const peer = peers[0];
    if (!peer) throw new Error('연결 필요');
    const event = value();
    send(peer, 'state.changed', event, event.eventId);
    await vi.waitFor(() => {
      expect(received).toHaveBeenCalledWith(event);
    });
    expect(transport.getState()).toBe('connected');
    expect(requests).toHaveLength(1);
  });
  it('F002-AC01/02 Bearer·대상을 전달하고 같은 대상 연결을 공유하며 마지막 해제 때 취소한다', async () => {
    transport.connect(token);
    const first = transport.subscribe(target);
    const second = transport.subscribe(target);
    await vi.waitFor(() => {
      expect(peers).toHaveLength(1);
    });
    expect(requests[0]?.headers.authorization).toBe('Bearer test-access');
    expect(requests[0]?.headers.cookie).toBeUndefined();
    expect(requests[0]?.url).toContain('targetType=DEPARTMENT_TICKETS');
    expect(requests[0]?.url).toContain(target.id);
    first();
    expect(transport.getState()).toBe('connected');
    second();
    await vi.waitFor(() => {
      expect(peers[0]?.destroyed).toBe(true);
    });
    expect(transport.getState()).toBe('idle');
  });
  it('F002-AC03 403은 대상 거부를 알리고 재접속하지 않는다', async () => {
    handle = (_req, res) => {
      res.writeHead(403, { 'Content-Type': 'application/json' });
      res.end(
        JSON.stringify({
          code: 'ACCESS_DENIED',
          message: '거부',
          requestId: crypto.randomUUID(),
          retryable: false,
        }),
      );
    };
    const state = vi.fn();
    transport.connect(token);
    transport.subscribe(target, state);
    await vi.waitFor(() => {
      expect(state).toHaveBeenCalledWith('denied');
    });
    expect(requests).toHaveLength(1);
  });
  it('F002-AC04/08 target.id 형식을 검사하고 같은 이벤트를 한 번만 전달한다', async () => {
    const received = vi.fn();
    transport.on('state.changed', received);
    transport.connect(token);
    transport.subscribe(target);
    await vi.waitFor(() => {
      expect(peers).toHaveLength(1);
    });
    const event = value();
    const peer = peers[0];
    if (!peer) throw new Error('연결 필요');
    send(peer, 'state.changed', event, event.eventId);
    send(peer, 'state.changed', event, event.eventId);
    await vi.waitFor(() => {
      expect(received).toHaveBeenCalledOnce();
    });
    expect(received).toHaveBeenCalledWith({ ...event, target });
  });
  it('F002-AC07 마지막 처리 커서로 재연결하고 연결마다 REST 동기화를 요청한다', async () => {
    const sync = vi.fn(() => Promise.resolve());
    const received = vi.fn();
    transport.onSync(sync);
    transport.on('state.changed', received);
    transport.connect(token);
    transport.subscribe(target);
    await vi.waitFor(() => {
      expect(peers).toHaveLength(1);
    });
    const event = value();
    const peer = peers[0];
    if (!peer) throw new Error('연결 필요');
    send(peer, 'state.changed', event, event.eventId);
    await vi.waitFor(() => {
      expect(received).toHaveBeenCalledOnce();
    });
    peer.end();
    await vi.waitFor(() => {
      expect(requests).toHaveLength(2);
    });
    expect(requests[1]?.headers['last-event-id']).toBe(event.eventId);
    await vi.waitFor(() => {
      expect(sync).toHaveBeenCalledTimes(2);
    });
  });
  it('F002-AC12 제어 알림이 유실된 cursor 요청 EOF도 복구하고 커서 없이 재연결한다', async () => {
    const base = handle;
    handle = (req, res) => {
      if (req.headers['last-event-id']) {
        res.writeHead(200, { 'Content-Type': 'text/event-stream' });
        res.end(': 임의 진단 문구\n\n');
      } else base(req, res);
    };
    const sync = vi.fn(() => Promise.resolve());
    const received = vi.fn();
    transport.onSync(sync);
    transport.on('state.changed', received);
    transport.connect(token);
    transport.subscribe(target);
    await vi.waitFor(() => {
      expect(peers).toHaveLength(1);
    });
    const event = value();
    const peer = peers[0];
    if (!peer) throw new Error('연결 필요');
    send(peer, 'state.changed', event, event.eventId);
    await vi.waitFor(() => {
      expect(received).toHaveBeenCalledOnce();
    });
    peer.end();
    await vi.waitFor(() => {
      expect(requests.length).toBeGreaterThanOrEqual(3);
    });
    expect(requests[1]?.headers['last-event-id']).toBe(event.eventId);
    expect(requests[2]?.headers['last-event-id']).toBeUndefined();
    expect(sync.mock.calls.length).toBeGreaterThanOrEqual(3);
  });
  it('F002-AC12 확정된 resync.required를 수신하고 제어 ID를 재연결 커서로 사용하지 않는다', async () => {
    const sync = vi.fn(() => Promise.resolve());
    const resync = vi.fn();
    transport.onSync(sync);
    transport.on('resync.required', resync);
    transport.connect(token);
    transport.subscribe(target);
    await vi.waitFor(() => {
      expect(peers).toHaveLength(1);
    });
    const peer = peers[0];
    if (!peer) throw new Error('연결 필요');
    const controlId = crypto.randomUUID();
    send(peer, 'resync.required', { eventId: controlId }, controlId);
    await vi.waitFor(() => {
      expect(requests.length).toBeGreaterThanOrEqual(2);
    });
    expect(resync).toHaveBeenCalledExactlyOnceWith({ eventId: controlId });
    expect(requests[1]?.headers['last-event-id']).toBeUndefined();
  });
  it('F002-AC12 REST 복구가 완료되기 전에는 새 연결을 열지 않는다', async () => {
    let release: (() => void) | undefined;
    let recovering = false;
    transport.onSync(() =>
      recovering
        ? new Promise<void>((resolve) => {
            release = resolve;
          })
        : Promise.resolve(),
    );
    transport.connect(token);
    transport.subscribe(target);
    await vi.waitFor(() => {
      expect(peers).toHaveLength(1);
    });
    recovering = true;
    const controlId = crypto.randomUUID();
    const peer = peers[0];
    if (!peer) throw new Error('연결 필요');
    send(peer, 'resync.required', { eventId: controlId }, controlId);
    await vi.waitFor(() => {
      expect(release).toBeTypeOf('function');
    });
    expect(requests).toHaveLength(1);
    recovering = false;
    release?.();
    await vi.waitFor(() => {
      expect(requests).toHaveLength(2);
    });
    expect(requests[1]?.headers['last-event-id']).toBeUndefined();
  });
  it('F002-AC09 HTTP 401은 갱신 후 새 Bearer로 연결한다', async () => {
    registerRefreshHandler(() => {
      token = 'new-access';
      return Promise.resolve(true);
    });
    const base = handle;
    handle = (req, res) => {
      if (req.headers.authorization === 'Bearer test-access') {
        res.writeHead(401, { 'Content-Type': 'application/json' });
        res.end(
          JSON.stringify({
            code: 'ACCESS_TOKEN_EXPIRED',
            message: '만료',
            requestId: crypto.randomUUID(),
            retryable: false,
          }),
        );
      } else base(req, res);
    };
    transport.connect(token);
    transport.subscribe(target);
    await vi.waitFor(() => {
      expect(peers).toHaveLength(1);
    });
    expect(requests[1]?.headers.authorization).toBe('Bearer new-access');
  });
  it('F002-AC11 열린 스트림의 권한 회수 이벤트는 재로그인으로 넘긴다', async () => {
    transport.connect(token);
    transport.subscribe(target);
    await vi.waitFor(() => {
      expect(peers).toHaveLength(1);
    });
    const peer = peers[0];
    if (!peer) throw new Error('연결 필요');
    const event = { eventId: crypto.randomUUID(), code: 'AUTH_CONTEXT_CHANGED', message: '변경' };
    send(peer, 'auth.invalidated', event, event.eventId);
    await vi.waitFor(() => {
      expect(token).toBe('');
    });
    expect(requests).toHaveLength(1);
  });
  it('F002-AC06 초기 503은 재시도 예산 후 멈추고 자동 저장 명령을 보내지 않는다', async () => {
    handle = (_req, res) => {
      res.writeHead(503, { 'Content-Type': 'application/json' });
      res.end(
        JSON.stringify({
          code: 'SERVICE_UNAVAILABLE',
          message: '준비 중',
          requestId: crypto.randomUUID(),
          retryable: true,
        }),
      );
    };
    transport.connect(token);
    transport.subscribe(target);
    await vi.waitFor(() => {
      expect(transport.getState()).toBe('disconnected');
    });
    expect(requests.length).toBeGreaterThan(1);
    expect(requests.every((req) => req.method === 'GET')).toBe(true);
  });
});
