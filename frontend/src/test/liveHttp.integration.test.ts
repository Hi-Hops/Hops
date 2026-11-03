// @vitest-environment node
import { createServer } from 'node:http';
import type { IncomingMessage, ServerResponse } from 'node:http';
import type { AddressInfo } from 'node:net';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  apiRequest,
  registerTokenProvider,
  registerUnauthorizedHandler,
  registerRefreshHandler,
} from '@/shared/api/client';
import { createInquiryCommand } from '@/features/chat/api/systemChatApi';
import { sendSystemCommand } from '@/shared/api/systemCommands';
import { localError } from '@/shared/api/wire';

let handle: (req: IncomingMessage, res: ServerResponse) => void;
const server = createServer((req, res) => {
  handle(req, res);
});
let requests: IncomingMessage[];
let token: string;
let status: number;
let body: unknown;
beforeEach(async () => {
  token = 'synthetic-current';
  requests = [];
  status = 200;
  body = {};
  vi.stubEnv('VITE_DATA_MODE', 'live');
  registerTokenProvider(() => token);
  registerUnauthorizedHandler(() => {
    token = '';
  });
  registerRefreshHandler(() => Promise.resolve(false));
  handle = (req, res) => {
    requests.push(req);
    res
      .writeHead(status, { 'Content-Type': 'application/json', 'Retry-After': '2' })
      .end(JSON.stringify(body));
  };
  await new Promise<void>((resolve) => {
    server.listen(0, '127.0.0.1', resolve);
  });
  vi.stubEnv('VITE_API_BASE_URL', `http://127.0.0.1:${(server.address() as AddressInfo).port}`);
});
afterEach(async () => {
  server.closeAllConnections();
  await new Promise<void>((resolve) => {
    server.close(() => {
      resolve();
    });
  });
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

describe('hops-system-1 HTTP 오류·취소·저장 경계', () => {
  it('F003-CONTRACT-01 이전 /api 경로를 네트워크 호출 전에 거부한다', async () => {
    await expect(apiRequest('/api/inquiries')).rejects.toMatchObject({
      body: { code: 'INVALID_API_PATH' },
    });
    expect(requests).toHaveLength(0);
  });
  it('F003-CONTRACT-04 401 만료 후 갱신해도 저장 POST를 자동 반복하지 않는다', async () => {
    status = 401;
    body = localError('ACCESS_TOKEN_EXPIRED', '인증 만료');
    const refresh = vi.fn(() => {
      token = 'synthetic-refreshed';
      return Promise.resolve(true);
    });
    registerRefreshHandler(refresh);
    const command = createInquiryCommand({
      requestId: crypto.randomUUID(),
      content: '문의',
      reportedSystemId: null,
    });
    await expect(sendSystemCommand(command)).rejects.toMatchObject({ status: 401 });
    expect(refresh).toHaveBeenCalledOnce();
    expect(requests).toHaveLength(1);
    expect(requests[0]?.method).toBe('POST');
    expect(token).toBe('synthetic-refreshed');
  });
  it.each([403, 404, 409, 429, 503])(
    'F001-CONTRACT-03 HTTP %s와 서버 오류·Retry-After를 보존한다',
    async (httpStatus) => {
      status = httpStatus;
      body = localError('ACCESS_DENIED', '안전한 오류');
      await expect(apiRequest('/api/v2/tickets')).rejects.toMatchObject({
        status,
        body,
        retryAfterSeconds: status === 429 ? 2 : 0,
      });
    },
  );
  it('F001-CONTRACT-03 일반 401은 세션을 만료시키며 이전 토큰의 늦은 401은 새 세션을 유지한다', async () => {
    status = 401;
    body = localError('UNAUTHENTICATED', '로그인 필요');
    await expect(
      apiRequest('/api/v2/auth/me', { token: 'synthetic-previous' }),
    ).rejects.toMatchObject({ status: 401 });
    expect(token).toBe('synthetic-current');
    await expect(apiRequest('/api/v2/auth/me')).rejects.toMatchObject({ status: 401 });
    expect(token).toBe('');
  });
  it('F003-CONTRACT-04 응답 본문이 지연돼도 timeout을 적용한다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>().mockImplementation((_url, options) =>
        Promise.resolve(
          new Response(
            new ReadableStream<Uint8Array>({
              start(controller) {
                options?.signal?.addEventListener(
                  'abort',
                  () => {
                    controller.error(new DOMException('취소', 'AbortError'));
                  },
                  { once: true },
                );
              },
            }),
          ),
        ),
      ),
    );
    await expect(
      apiRequest('/api/v2/inquiries', {
        method: 'POST',
        idempotencyKey: crypto.randomUUID(),
        timeoutMs: 20,
      }),
    ).rejects.toMatchObject({ status: 0, body: { code: 'REQUEST_TIMEOUT' } });
  });
  it('F001-CONTRACT-03 JSON·네트워크 오류를 Mock 성공으로 대체하지 않는다', async () => {
    handle = (_req, res) => {
      res.writeHead(200, { 'Content-Type': 'text/html' }).end('<html>오류</html>');
    };
    await expect(apiRequest('/api/v2/auth/me')).rejects.toMatchObject({
      body: { code: 'INVALID_RESPONSE' },
    });
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockRejectedValue(new TypeError('network')));
    await expect(apiRequest('/api/v2/auth/me')).rejects.toMatchObject({
      body: { code: 'NETWORK_ERROR' },
    });
  });
  it.each([400, 403, 409])(
    'F003-CONTRACT-04 비계약 HTTP %s는 업무 거절이 아닌 저장 결과 미확인으로 처리한다',
    async (httpStatus) => {
      handle = (_req, res) => {
        res.writeHead(httpStatus, { 'Content-Type': 'text/html' }).end('<html>proxy error</html>');
      };
      await expect(
        sendSystemCommand(
          createInquiryCommand({
            requestId: crypto.randomUUID(),
            content: '문의',
            reportedSystemId: null,
          }),
        ),
      ).rejects.toMatchObject({ status: 0, body: { code: 'INVALID_RESPONSE' } });
      expect(token).toBe('synthetic-current');
    },
  );
  it('F009-CONTRACT-01 취소를 네트워크 오류로 바꾸지 않는다', async () => {
    const controller = new AbortController();
    controller.abort();
    await expect(
      apiRequest('/api/v2/tickets', { signal: controller.signal }),
    ).rejects.toMatchObject({ name: 'AbortError' });
  });
});
