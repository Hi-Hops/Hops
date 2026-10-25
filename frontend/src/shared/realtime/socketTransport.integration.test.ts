// @vitest-environment node
import { createServer } from 'node:http';
import type { AddressInfo } from 'node:net';
import { Server } from 'socket.io';
import type { Socket as ServerSocket } from 'socket.io';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { registerTokenProvider, registerUnauthorizedHandler } from '@/shared/api/client';
import { fixture } from '@/shared/api/mockData';
import { isRecord } from '@/shared/api/wire';
import type { StoredAck, SubscriptionCommand } from '@/shared/api/types';
import type { CommandMap, CommandName } from './types';
import { SocketTransport } from './socketTransport';

let server: Server;
let peer: ServerSocket | undefined;
let transport: SocketTransport;
let token: string;
let events: { name: CommandName; command: CommandMap[CommandName] }[];
let behavior: 'ok' | 'silent' | 'invalid' | 'malformed' | 'denied' | 'expired';
let expired: ReturnType<typeof vi.fn<() => void>>;
let rejectAuth: boolean;
const inquiry = fixture('inquiry-result', 'InquiryResult');
const message = fixture('messages', 'MessagePage').items[0];
if (!message) throw new Error('테스트 메시지 없음');
const stored: StoredAck = {
  ok: true,
  requestId: '',
  inquiryId: inquiry.id,
  inquiryVersion: 2,
  messageId: message.id,
  revision: 1,
  runId: crypto.randomUUID(),
  effect: 'ANALYSIS_STARTED',
};
const subscription = (): SubscriptionCommand => ({
  requestId: crypto.randomUUID(),
  target: { type: 'INQUIRY', id: inquiry.id },
});

beforeEach(async () => {
  events = [];
  behavior = 'ok';
  rejectAuth = false;
  peer = undefined;
  token = crypto.randomUUID();
  expired = vi.fn();
  registerTokenProvider(() => token);
  registerUnauthorizedHandler(expired);
  const http = createServer();
  server = new Server(http, { path: '/chat/socket.io' });
  server.use((socket, next) => {
    const auth: unknown = socket.handshake.auth;
    if (rejectAuth || !isRecord(auth) || auth.token !== token) {
      const error = Object.assign(new Error('인증 실패'), {
        data: {
          code: 'UNAUTHENTICATED',
          message: '만료',
          requestId: crypto.randomUUID(),
          retryable: false,
        },
      });
      next(error);
    } else next();
  });
  server.on('connection', (socket) => {
    peer = socket;
    for (const name of [
      'inquiry.create',
      'message.send',
      'message.revise',
      'subscription.request',
      'subscription.release',
    ] as const) {
      socket.on(name, (command: CommandMap[CommandName], ack: (reply: unknown) => void) => {
        events.push({ name, command });
        if (behavior === 'silent') return;
        if (behavior === 'invalid') {
          ack({ ok: true, requestId: 'wrong' });
          return;
        }
        if (behavior === 'malformed') {
          ack({ ok: true, requestId: command.requestId });
          return;
        }
        if (behavior === 'denied' || behavior === 'expired') {
          ack({
            ok: false,
            requestId: command.requestId,
            error: {
              code: behavior === 'expired' ? 'UNAUTHENTICATED' : 'ACCESS_DENIED',
              message: '권한 없음',
              requestId: command.requestId,
              retryable: false,
            },
          });
          return;
        }
        if ('target' in command)
          ack({ ok: true, requestId: command.requestId, target: command.target });
        else ack({ ...stored, requestId: command.requestId });
      });
    }
  });
  await new Promise<void>((resolve) => {
    http.listen(0, '127.0.0.1', resolve);
  });
  const address = http.address() as AddressInfo;
  vi.stubEnv('VITE_DATA_MODE', 'live');
  vi.stubEnv('VITE_API_BASE_URL', `http://127.0.0.1:${address.port}`);
  vi.stubEnv('VITE_SOCKET_URL', `http://127.0.0.1:${address.port}`);
  vi.stubEnv('VITE_SOCKET_PATH', '/chat/socket.io');
  transport = new SocketTransport(150);
});
afterEach(async () => {
  transport.disconnect();
  await new Promise<void>((resolve) => {
    void server.close(() => {
      resolve();
    });
  });
});
async function connect() {
  transport.connect(token);
  await vi.waitFor(() => {
    expect(transport.getState()).toBe('connected');
  });
}

describe('F002·F003·F004 실제 Socket.IO 어댑터', () => {
  it('F002-AC07 재접속 시간 창을 넘기면 자동 시도를 멈추고 수동 연결로 복구한다', async () => {
    transport.disconnect();
    transport = new SocketTransport(100, 80);
    await connect();
    peer?.conn.close();
    await vi.waitFor(
      () => {
        expect(transport.getState()).toBe('disconnected');
      },
      { timeout: 2000 },
    );
    await connect();
    expect(transport.getState()).toBe('connected');
    expect(expired).not.toHaveBeenCalled();
  });
  it('F002-AC01 토큰과 커스텀 path로 연결하고 모든 채팅 명령과 저장 ACK를 전달한다', async () => {
    await connect();
    const create = { requestId: crypto.randomUUID(), content: '원문\n유지' };
    const send = {
      ...create,
      requestId: crypto.randomUUID(),
      inquiryId: inquiry.id,
      expectedInquiryVersion: 1,
    };
    const revise = { ...send, requestId: crypto.randomUUID(), messageId: message.id };
    expect(await transport.send('inquiry.create', create)).toMatchObject({
      ok: true,
      requestId: create.requestId,
    });
    expect(await transport.send('message.send', send)).toMatchObject({
      ok: true,
      requestId: send.requestId,
    });
    expect(await transport.send('message.revise', revise)).toMatchObject({
      ok: true,
      requestId: revise.requestId,
    });
    expect(events).toEqual([
      { name: 'inquiry.create', command: create },
      { name: 'message.send', command: send },
      { name: 'message.revise', command: revise },
    ]);
  });
  it('F002-AC02 같은 대상의 여러 소비자는 마지막 해제까지 서버 구독을 유지한다', async () => {
    await connect();
    const first = subscription();
    const second = subscription();
    const [a, b] = await Promise.all([
      transport.send('subscription.request', first),
      transport.send('subscription.request', second),
    ]);
    expect(a).toMatchObject({ ok: true, requestId: first.requestId });
    expect(b).toMatchObject({ ok: true, requestId: second.requestId });
    await transport.send('subscription.release', first);
    expect(events.map((event) => event.name)).toEqual(['subscription.request']);
    await transport.send('subscription.release', second);
    expect(events.map((event) => event.name)).toEqual([
      'subscription.request',
      'subscription.release',
    ]);
  });
  it('F002-AC08 진행·답변 조각·저장·재조회 신호를 각각 전달하고 listener를 해제한다', async () => {
    await connect();
    const progress = vi.fn();
    const delta = vi.fn();
    const saved = vi.fn();
    const changed = vi.fn();
    const off = transport.on('agent.progress', progress);
    transport.on('answer.delta', delta);
    transport.on('message.stored', saved);
    transport.on('state.changed', changed);
    const p = fixture('progress', 'AgentProgressEvent');
    const d = fixture('delta-1', 'AnswerDeltaEvent');
    const s = fixture('stored', 'MessageStoredEvent');
    const c = { eventId: crypto.randomUUID(), target: subscription().target, inquiryVersion: 2 };
    server.emit('agent.progress', p);
    server.emit('answer.delta', d);
    server.emit('message.stored', s);
    server.emit('state.changed', c);
    await vi.waitFor(() => {
      expect(progress).toHaveBeenCalledWith(p);
      expect(delta).toHaveBeenCalledWith(d);
      expect(saved).toHaveBeenCalledWith(s);
      expect(changed).toHaveBeenCalledWith(c);
    });
    off();
    server.emit('agent.progress', p);
    await new Promise((resolve) => setTimeout(resolve, 30));
    expect(progress).toHaveBeenCalledOnce();
  });
  it('F003-AC04 ACK가 없으면 결과 미확인으로 두고 동일 요청 재전송만 처리한다', async () => {
    await connect();
    const command = { requestId: crypto.randomUUID(), content: '테스트' };
    behavior = 'silent';
    expect(await transport.send('inquiry.create', command)).toEqual({ ok: 'unknown' });
    expect(events).toHaveLength(1);
    behavior = 'ok';
    expect(await transport.send('inquiry.create', command)).toMatchObject({
      ok: true,
      requestId: command.requestId,
    });
    expect(events[1]?.command).toEqual(command);
  });
  it.each(['invalid', 'malformed'] as const)(
    'F003-AC04 계약에 맞지 않는 ACK를 저장 성공으로 확정하지 않는다: %s',
    async (mode) => {
      await connect();
      behavior = mode;
      expect(
        await transport.send('inquiry.create', {
          requestId: crypto.randomUUID(),
          content: '테스트',
        }),
      ).toEqual({ ok: 'unknown' });
    },
  );
  it('F002-AC02 구독 거부는 전달하되 소켓 연결은 유지한다', async () => {
    await connect();
    behavior = 'denied';
    expect(await transport.send('subscription.request', subscription())).toMatchObject({
      ok: false,
      error: { code: 'ACCESS_DENIED' },
    });
    expect(transport.getState()).toBe('connected');
  });
  it('F001-AC05 명령의 인증 만료 ACK를 현재 세션에 알린다', async () => {
    await connect();
    behavior = 'expired';
    expect(
      await transport.send('inquiry.create', {
        requestId: crypto.randomUUID(),
        content: '인증 만료',
      }),
    ).toEqual({ ok: 'unknown' });
    await vi.waitFor(() => {
      expect(expired).toHaveBeenCalledOnce();
    });
  });
  it('F002-AC01 채팅 서버 네트워크 장애를 로그인 만료로 바꾸지 않는다', async () => {
    vi.stubEnv('VITE_SOCKET_URL', 'http://127.0.0.1:1');
    transport.connect(token);
    await vi.waitFor(() => {
      expect(transport.getState()).toBe('disconnected');
    });
    expect(expired).not.toHaveBeenCalled();
  });
  it('F002-AC09 연결 인증 실패는 현재 세션 만료로 알린다', async () => {
    transport.connect('invalid-token');
    await vi.waitFor(() => {
      expect(transport.getState()).toBe('disconnected');
    });
    // 요청 토큰이 현재 세션과 다른 경우 새 세션을 만료시키지 않는다.
    expect(expired).not.toHaveBeenCalled();
    rejectAuth = true;
    transport.connect(token);
    await vi.waitFor(() => {
      expect(expired).toHaveBeenCalledOnce();
    });
    expect(transport.getState()).toBe('disconnected');
  });
  it('F002-AC07 재접속은 연결 알림 뒤 현재 권한으로 다시 구독한다', async () => {
    await connect();
    await transport.send('subscription.request', subscription());
    const refreshed = vi.fn();
    transport.onConnection((state) => {
      if (state === 'connected')
        void transport.send('subscription.request', subscription()).then(refreshed);
    });
    peer?.conn.close();
    await vi.waitFor(
      () => {
        expect(refreshed).toHaveBeenCalledWith(expect.objectContaining({ ok: true }));
      },
      { timeout: 5000 },
    );
    expect(events.filter((event) => event.name === 'subscription.request')).toHaveLength(2);
  });
  it('F002-AC10 로그아웃과 연결 종료는 대기 명령을 미확인으로 정리하고 오프라인 전송을 보류한다', async () => {
    await connect();
    behavior = 'silent';
    const pending = transport.send('inquiry.create', {
      requestId: crypto.randomUUID(),
      content: '테스트',
    });
    transport.disconnect();
    expect(await pending).toEqual({ ok: 'unknown' });
    expect(
      await transport.send('inquiry.create', {
        requestId: crypto.randomUUID(),
        content: '오프라인',
      }),
    ).toEqual({ ok: 'unknown' });
    expect(transport.getState()).toBe('disconnected');
  });
});
