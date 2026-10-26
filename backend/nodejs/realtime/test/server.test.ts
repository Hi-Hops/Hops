import type { TestContext } from 'node:test';
import type { Socket, ManagerOptions, SocketOptions } from 'socket.io-client';
import type { AddressInfo } from 'node:net';
import type {
  CurrentUser,
  InquiryCreateCommand,
  MessageReviseCommand,
  StoredAck,
  SubscriptionTarget,
  ApiErrorBody,
  PublicEvents,
  BusinessEventMap,
} from '../src/contracts/types.js';

interface FixtureOptions {
  ttl?: number;
  ackTimeout?: number;
  beforeAuthorize?: () => void | Promise<void>;
}

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { once, type EventEmitter } from 'node:events';
import { createServer } from 'node:http';
import { io } from 'socket.io-client';
import { createRealtimeServer } from '../src/server.js';
import { createSpringClient } from '../src/clients/spring.client.js';
import { command, content, failure, storedAck } from '../src/contracts/validation.js';

interface TestEvents {
  connect: [];
  connect_error: [Error & { data: ApiErrorBody }];
  disconnect: [string];
  'message.stored': [BusinessEventMap['message.stored']];
  'auth.invalidated': Parameters<PublicEvents['auth.invalidated']>;
  'relay.status': Parameters<PublicEvents['relay.status']>;
}

function socketOnce<K extends keyof TestEvents>(socket: Socket, event: K): Promise<TestEvents[K]> {
  // component-emitter는 once/on/removeListener를 제공하지만 Node EventEmitter 전체 타입을 구현하지 않는다.
  return once(socket as unknown as EventEmitter, event) as Promise<TestEvents[K]>;
}

const userId = randomUUID();

const user: CurrentUser = {
  id: userId,
  employeeNo: 'fixture-001',
  name: '테스트 사용자',
  department: {
    id: randomUUID(),
    code: 'FIXTURE',
    name: '가상 부서',
    isRoutable: false,
  },
  roles: [],
};

const inquiryId = randomUUID();

const target: SubscriptionTarget = { type: 'INQUIRY', id: inquiryId };

const request = () => ({ requestId: randomUUID(), target });

const stored = (cmd: InquiryCreateCommand): StoredAck => ({
  ok: true,
  requestId: cmd.requestId,
  inquiryId,
  inquiryVersion: 1,
  messageId: randomUUID(),
  revision: 1,
  runId: randomUUID(),
  effect: 'ANALYSIS_STARTED',
});

const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

async function fixture(t: TestContext, options: FixtureOptions = {}) {
  let allowed = true,
    valid = true;
  const spring = {
    async authenticate(token: string) {
      if (!valid || token !== 'fixture-user-token') throw failure('AUTH_CONTEXT_CHANGED');

      return {
        user,
        expiresAt: new Date(Date.now() + (options.ttl ?? 60000)).toISOString(),
      };
    },
    async authorize(_token: string, dest: SubscriptionTarget) {
      await options.beforeAuthorize?.();

      if (!allowed || dest.id !== inquiryId) throw failure('ACCESS_DENIED');
    },
  };
  const server = createRealtimeServer(
    {
      springUrl: 'http://127.0.0.1:8080',
      nodeCredential: 'fixture-node',
      springCredential: 'fixture-spring',
      origins: ['http://localhost:5173'],
      ackTimeout: options.ackTimeout ?? 500,
    },
    spring,
  );
  server.http.listen(0, '127.0.0.1');
  await once(server.http, 'listening');
  const url = `http://127.0.0.1:${(server.http.address() as AddressInfo).port}`;
  const sockets: Socket[] = [];
  t.after(async () => {
    for (const socket of sockets) socket.disconnect();

    await server.close();
  });
  const open = (
    namespace = '',
    auth: Record<string, string> = { token: 'fixture-user-token' },
    extra: Partial<ManagerOptions & SocketOptions> = {},
  ) => {
    const client = io(url + namespace, {
      auth,
      forceNew: true,
      reconnection: false,
      autoConnect: false,
      ...extra,
    });
    sockets.push(client);

    return client;
  };
  const connect = async (client: Socket) => {
    const connected = socketOnce(client, 'connect');
    client.connect();
    await connected;

    return client;
  };
  const service = async () => connect(open('/spring', { serviceToken: 'fixture-spring' }));

  return {
    server,
    url,
    open,
    connect,
    service,
    deny: () => {
      allowed = false;
    },
    invalidate: () => {
      valid = false;
    },
  };
}

test('입력 경계: Unicode 코드 포인트, 공백 집합, surrogate와 버전', () => {
  assert.equal(content('😀'.repeat(4000)), true);

  for (const value of ['😀'.repeat(4001), '', '\u0085\uFEFF', '\ud800'])
    assert.equal(content(value), false);

  assert.equal(content(' 원문 유지 '), true);
  assert.equal(
    command('message.send', {
      requestId: randomUUID(),
      inquiryId,
      expectedInquiryVersion: '1',
      content: '문의',
    }),
    false,
  );
  assert.equal(
    command('inquiry.create', {
      requestId: randomUUID(),
      content: '문의',
      userId,
    }),
    false,
  );
  const input = { requestId: randomUUID(), content: '문의' };
  assert.equal(storedAck({ ...stored(input), runId: null }, input), false);
});

test('연결 인증 거부는 connect_error.data로 전달하고 서비스 위장을 차단한다', async (t) => {
  const f = await fixture(t);

  for (const client of [
    f.open('', { token: 'wrong' }),
    f.open('/spring', { serviceToken: 'wrong' }),
  ]) {
    const rejected = socketOnce(client, 'connect_error');
    client.connect();
    const [error] = await rejected;
    assert.equal(error.data.retryable, false);
    assert.equal(client.connected, false);
  }

  const client = f.open(
    '',
    { token: 'fixture-user-token' },
    { extraHeaders: { Origin: 'https://untrusted.example' } },
  );
  const rejected = socketOnce(client, 'connect_error');
  client.connect();
  await rejected;
});

test('polling과 WebSocket 모두 연결되고 Spring 저장 ACK만 그대로 전달한다', async (t) => {
  const f = await fixture(t);
  const service = await f.service();
  let calls = 0;
  service.on('inquiry.create', (envelope, ack) => {
    assert.equal(envelope.accessToken, 'fixture-user-token');
    assert.equal(envelope.command.content, ' 문의 ');
    calls++;
    ack(stored(envelope.command));
  });

  for (const transport of ['polling', 'websocket']) {
    const client = await f.connect(f.open('', undefined, { transports: [transport] }));
    const input = { requestId: randomUUID(), content: ' 문의 ' };
    const reply = await client.timeout(1000).emitWithAck('inquiry.create', input);
    assert.equal(reply.ok, true);
    assert.equal(reply.requestId, input.requestId);
    client.disconnect();
  }

  assert.equal(calls, 2);
});

test('소유자 구독, 다른 문의 거부, 사용자 이벤트 위조 및 현재 권한 회수', async (t) => {
  const f = await fixture(t);
  const service = await f.service();
  const client = await f.connect(f.open());
  const other = await f.connect(f.open());
  assert.equal((await client.emitWithAck('subscription.request', request())).ok, true);
  const denied = await other.emitWithAck('subscription.request', {
    requestId: randomUUID(),
    target: { type: 'INQUIRY', id: randomUUID() },
  });
  assert.equal(denied.error.code, 'ACCESS_DENIED');
  const received: unknown[] = [],
    leaks: unknown[] = [];
  client.on('message.stored', (e) => received.push(e));
  other.on('message.stored', (e) => leaks.push(e));
  const event = {
    eventId: randomUUID(),
    inquiryId,
    inquiryVersion: 1,
    messageId: randomUUID(),
    requestId: null,
  };
  other.emit('message.stored', event);
  await wait(30);
  assert.equal(received.length, 0);
  const arrived = socketOnce(client, 'message.stored');
  service.emit('message.stored', event);
  await arrived;
  assert.equal(received.length, 1);
  assert.equal(leaks.length, 0);
  f.deny();
  service.emit('message.stored', { ...event, eventId: randomUUID() });
  await wait(40);
  assert.equal(received.length, 1);
  assert.equal((await client.emitWithAck('subscription.release', request())).ok, true);
  assert.equal((await client.emitWithAck('subscription.release', request())).ok, true);
});

test('Spring 연결 없음/ACK 유실/잘못된 ACK는 저장 성공으로 바꾸지 않는다', async (t) => {
  const f = await fixture(t, { ackTimeout: 40 });
  const client = await f.connect(f.open());
  const input = { requestId: randomUUID(), content: '문의' };
  assert.equal(
    (await client.emitWithAck('inquiry.create', input)).error.code,
    'SERVICE_UNAVAILABLE',
  );
  const service = await f.service();
  assert.equal(
    (await client.emitWithAck('inquiry.create', input)).error.code,
    'SERVICE_UNAVAILABLE',
  );
  service.on('inquiry.create', (_, ack) => ack({ ok: true }));
  assert.equal(
    (await client.emitWithAck('inquiry.create', input)).error.code,
    'SERVICE_UNAVAILABLE',
  );
});

test('동일 요청은 변경 없이 Spring에 전달하며 멱등 충돌 오류도 보존한다', async (t) => {
  const f = await fixture(t);
  const service = await f.service();
  const client = await f.connect(f.open());
  let first: InquiryCreateCommand | undefined, reply: StoredAck | undefined;
  service.on('inquiry.create', ({ command: cmd }, ack) => {
    if (!first) {
      first = cmd;
      reply = stored(cmd);
    }

    assert.ok(first);
    ack(
      cmd.content === first.content
        ? reply
        : {
            ok: false,
            requestId: cmd.requestId,
            error: failure('IDEMPOTENCY_KEY_REUSED', cmd.requestId),
          },
    );
  });
  const input = { requestId: randomUUID(), content: '문의' };
  const a = await client.emitWithAck('inquiry.create', input);
  assert.deepEqual(await client.emitWithAck('inquiry.create', input), a);
  assert.equal(
    (await client.emitWithAck('inquiry.create', { ...input, content: '변경' })).error.code,
    'IDEMPOTENCY_KEY_REUSED',
  );
});

test('토큰 만료와 Spring 인증 회수는 구독을 먼저 제거하고 연결을 종료한다', async (t) => {
  const f = await fixture(t, { ttl: 200 });
  const service = await f.service();
  const client = await f.connect(f.open());
  await client.emitWithAck('subscription.request', request());
  const invalidated = socketOnce(client, 'auth.invalidated');
  const disconnected = socketOnce(client, 'disconnect');
  const [event] = await invalidated;
  await disconnected;
  assert.equal(event.code, 'ACCESS_TOKEN_EXPIRED');
  const second = await f.connect(f.open());
  const revoked = socketOnce(second, 'auth.invalidated');
  const ended = socketOnce(second, 'disconnect');
  service.emit('auth.invalidated', { userId, code: 'AUTH_CONTEXT_CHANGED' });
  assert.equal((await revoked)[0].code, 'AUTH_CONTEXT_CHANGED');
  await ended;
  assert.equal(f.server.io.of('/').sockets.size, 0);
});

test('중계 단절은 구독을 회수하고 복구 후 명시적 재구독이 필요하다', async (t) => {
  const f = await fixture(t);
  const service = await f.service();
  const client = await f.connect(f.open());
  await client.emitWithAck('subscription.request', request());
  const unavailable = socketOnce(client, 'relay.status');
  service.disconnect();
  assert.equal((await unavailable)[0].status, 'UNAVAILABLE');
  const available = socketOnce(client, 'relay.status');
  await f.service();
  assert.equal((await available)[0].status, 'AVAILABLE');
  const serverSocket = f.server.io.of('/').sockets.get(client.id!)!;
  assert.equal(serverSocket.rooms.size, 1);
  assert.equal((await client.emitWithAck('subscription.request', request())).ok, true);
});

test('실제 HTTP 내부 검증: 서비스 Bearer/사용자 토큰 분리, 잘못된 응답/장애 처리', async (t) => {
  let mode = 'ok';
  const http = createServer(async (req, res) => {
    let raw = '';

    for await (const chunk of req) raw += chunk;

    const body = JSON.parse(raw);
    assert.equal(req.headers.authorization, 'Bearer fixture-node');
    assert.equal(body.accessToken, 'fixture-user-token');
    res.setHeader('Content-Type', 'application/json');

    if (mode === 'denied') {
      res.writeHead(403);
      res.end(JSON.stringify(failure('ACCESS_DENIED')));
    } else if (mode === 'bad') res.end('{}');
    else
      res.end(
        JSON.stringify(
          req.url?.endsWith('authenticate')
            ? {
                user,
                expiresAt: new Date(Date.now() + 60000).toISOString(),
              }
            : { target: body.target },
        ),
      );
  });
  http.listen(0, '127.0.0.1');
  await once(http, 'listening');
  t.after(() => new Promise<void>((resolve) => http.close(() => resolve())));
  const client = createSpringClient({
    springUrl: `http://127.0.0.1:${(http.address() as AddressInfo).port}`,
    nodeCredential: 'fixture-node',
  });
  assert.equal((await client.authenticate('fixture-user-token')).user.id, userId);
  await client.authorize('fixture-user-token', target);
  mode = 'denied';
  await assert.rejects(client.authorize('fixture-user-token', target), {
    code: 'ACCESS_DENIED',
  });
  mode = 'bad';
  await assert.rejects(client.authenticate('fixture-user-token'), {
    code: 'SERVICE_UNAVAILABLE',
  });
});

// 제어 가능한 권한 응답으로 연결 회수와 비동기 검증의 경합을 재현한다.
function authorizationGate() {
  const entered = Promise.withResolvers<void>();
  const released = Promise.withResolvers<void>();

  return {
    entered: entered.promise,
    release: released.resolve,
    beforeAuthorize: () => {
      entered.resolve();

      return released.promise;
    },
  };
}

test('구독 조회 중 release가 도착하면 최종 구독이 해제된다', { timeout: 2000 }, async (t) => {
  const gate = authorizationGate();
  const f = await fixture(t, gate);
  await f.service();
  const client = await f.connect(f.open());
  const subscribed = client.timeout(1000).emitWithAck('subscription.request', request());
  await gate.entered;
  const released = client.timeout(1000).emitWithAck('subscription.release', request());
  gate.release();
  assert.equal((await subscribed).ok, true);
  assert.equal((await released).ok, true);
  assert.equal(f.server.io.of('/').sockets.get(client.id!)!.rooms.size, 1);
});

test(
  '구독 조회 중 인증 회수 후 늦은 권한 허용이 room을 복원하지 않는다',
  { timeout: 2000 },
  async (t) => {
    const gate = authorizationGate();
    const f = await fixture(t, gate);
    const service = await f.service();
    const client = await f.connect(f.open());
    const serverSocket = f.server.io.of('/').sockets.get(client.id!)!;
    client.emit('subscription.request', request(), () => {});
    await gate.entered;
    const ended = socketOnce(client, 'disconnect');
    service.emit('auth.invalidated', { userId, code: 'AUTH_CONTEXT_CHANGED' });
    await ended;
    gate.release();
    await wait(20);
    assert.equal(serverSocket.rooms.size, 0);
    assert.equal(serverSocket.data.revoked, true);
  },
);

test(
  '저장 ACK 대기 중 Spring 연결 단절 시 성공 ACK를 만들지 않는다',
  { timeout: 2000 },
  async (t) => {
    const f = await fixture(t);
    const service = await f.service();
    const client = await f.connect(f.open());
    service.on('inquiry.create', () => service.disconnect());
    const input = { requestId: randomUUID(), content: '테스트 문의' };
    const result = await client.timeout(1000).emitWithAck('inquiry.create', input);
    assert.equal(result.ok, false);
    assert.equal(result.requestId, input.requestId);
    assert.equal(result.error.code, 'SERVICE_UNAVAILABLE');
  },
);

test('추가 메시지와 정정은 문의 버전·메시지 ID와 정보 보완 ACK를 보존한다', async (t) => {
  const f = await fixture(t);
  const service = await f.service();
  const client = await f.connect(f.open());

  for (const name of ['message.send', 'message.revise']) {
    const input: Omit<MessageReviseCommand, 'messageId'> & {
      messageId?: string;
    } = {
      requestId: randomUUID(),
      inquiryId,
      expectedInquiryVersion: 2,
      content: ' 정보 보완 ',
    };

    if (name === 'message.revise') input.messageId = randomUUID();

    service.once(name, ({ command: received }, ack) => {
      assert.deepEqual(received, input);
      ack({
        ...stored(input),
        inquiryVersion: 3,
        messageId: input.messageId ?? randomUUID(),
        effect: 'SUPPLEMENT_SAVED',
        runId: null,
      });
    });
    const reply = await client.timeout(1000).emitWithAck(name, input);
    assert.equal(reply.ok, true);
    assert.equal(reply.inquiryVersion, 3);
    assert.equal(reply.effect, 'SUPPLEMENT_SAVED');
    assert.equal(reply.runId, null);

    if (input.messageId) assert.equal(reply.messageId, input.messageId);
  }
});

test(
  '권한 조회 대기 중 저장 ACK 제한 시간이 지나면 구독하지 않는다',
  { timeout: 2000 },
  async (t) => {
    const gate = authorizationGate();
    const f = await fixture(t, { ...gate, ackTimeout: 20 });
    await f.service();
    const client = await f.connect(f.open());
    const result = client.timeout(1000).emitWithAck('subscription.request', request());
    await gate.entered;
    await wait(30);
    gate.release();
    assert.equal((await result).error.code, 'SERVICE_UNAVAILABLE');
    assert.equal(f.server.io.of('/').sockets.get(client.id!)!.rooms.size, 1);
  },
);

test('Spring의 두 번째 서비스 연결을 거부한다', { timeout: 2000 }, async (t) => {
  const f = await fixture(t);
  await f.service();
  const duplicate = f.open('/spring', { serviceToken: 'fixture-spring' });
  const rejected = socketOnce(duplicate, 'connect_error');
  duplicate.connect();
  assert.equal((await rejected)[0].data.code, 'ACCESS_DENIED');
});

test(
  '내부 HTTP 검증은 응답이 없으면 3초 후 서비스 장애로 종료한다',
  { timeout: 6000 },
  async (t) => {
    const http = createServer(() => {});
    http.listen(0, '127.0.0.1');
    await once(http, 'listening');
    t.after(() => {
      http.closeAllConnections();

      return new Promise<void>((resolve) => http.close(() => resolve()));
    });
    const client = createSpringClient({
      springUrl: `http://127.0.0.1:${(http.address() as AddressInfo).port}`,
      nodeCredential: 'fixture-node',
    });
    const started = performance.now();
    await assert.rejects(client.authenticate('fixture-user-token'), {
      code: 'SERVICE_UNAVAILABLE',
    });
    assert.ok(performance.now() - started >= 2800);
  },
);

test(
  '서로 다른 서버 인스턴스의 Spring 연결과 단절 상태가 분리된다',
  { timeout: 3000 },
  async (t) => {
    const first = await fixture(t);
    const second = await fixture(t);
    const firstService = await first.service();
    const secondService = await second.service();
    const firstClient = await first.connect(first.open());
    const secondClient = await second.connect(second.open());
    secondService.on('inquiry.create', ({ command: input }, ack) => ack(stored(input)));
    const unavailable = socketOnce(firstClient, 'relay.status');
    firstService.disconnect();
    assert.equal((await unavailable)[0].status, 'UNAVAILABLE');
    const input = { requestId: randomUUID(), content: '독립 서버 테스트 문의' };
    const reply = await secondClient.timeout(1000).emitWithAck('inquiry.create', input);
    assert.equal(reply.ok, true);
    assert.equal(reply.requestId, input.requestId);
  },
);
