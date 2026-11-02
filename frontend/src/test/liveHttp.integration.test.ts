// @vitest-environment node
import { createServer } from 'node:http';
import type { Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fixture } from '@/shared/api/mockData';
import {
  apiRequest,
  ApiError,
  registerTokenProvider,
  registerUnauthorizedHandler,
} from '@/shared/api/client';
import { login, logout, useSession } from '@/features/auth';
import { postMutation } from '@/shared/api/mutations';
import { registerRefreshHandler } from '@/shared/api/client';
import { createInquiry, sendMessage, reviseMessage } from '@/features/chat/api/chatApi';
import type { InquiryResult, MessagePage, TicketDetail, TicketPage } from '@/shared/api/types';

let server: Server;
let baseUrl: string;
let token: string;
let responses: {
  path: string;
  authorization: string | undefined;
  method: string | undefined;
  body: string;
  idempotencyKey: string | string[] | undefined;
}[];
let status: number;
let rawBody: string | null;
beforeEach(async () => {
  status = 200;
  rawBody = null;
  token = crypto.randomUUID();
  responses = [];
  server = createServer((req, res) => {
    let body = '';
    req.setEncoding('utf8');
    req.on('data', (chunk: string) => {
      body += chunk;
    });
    req.on('end', () => {
      const path = req.url ?? '/';
      responses.push({
        path,
        authorization: req.headers.authorization,
        method: req.method,
        body,
        idempotencyKey: req.headers['idempotency-key'],
      });
      res.setHeader('Content-Type', 'application/json');
      if (rawBody !== null) {
        res.writeHead(status).end(rawBody);
        return;
      }
      const user = fixture('user-1', 'CurrentUser');
      const inquiry = fixture('inquiry-result', 'InquiryResult');
      const ticket = fixture('ticket-detail', 'TicketDetail');
      let value: unknown;
      if (path === '/api/auth/login')
        value = {
          ...fixture('login-1', 'LoginResponse'),
          accessToken: token,
          expiresAt: new Date(Date.now() + 60_000).toISOString(),
        };
      else if (path === '/api/auth/me') value = user;
      else if (path === '/api/auth/logout') {
        res.writeHead(204).end();
        return;
      } else if (path.startsWith('/api/tickets?'))
        value = { items: [ticket], page: 0, size: 20, totalElements: 1 };
      else if (path.startsWith('/api/tickets/')) value = ticket;
      else if (path.includes('/messages?')) value = fixture('messages', 'MessagePage');
      else value = inquiry;
      res.writeHead(status).end(JSON.stringify(value));
    });
  });
  await new Promise<void>((resolve) => {
    server.listen(0, '127.0.0.1', resolve);
  });
  const address = server.address() as AddressInfo;
  baseUrl = `http://127.0.0.1:${address.port}`;
  vi.stubEnv('VITE_DATA_MODE', 'live');
  vi.stubEnv('VITE_API_BASE_URL', baseUrl);
  registerTokenProvider(() => useSession.getState().token);
  registerUnauthorizedHandler(() => {
    useSession.getState().expire();
  });
});
afterEach(async () => {
  useSession.getState().logout();
  server.closeAllConnections();
  await new Promise<void>((resolve, reject) => {
    server.close((error) => {
      if (error) reject(error);
      else resolve();
    });
  });
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

describe('F001·F003·F009 실제 HTTP 어댑터', () => {
  it.each(['create', 'send', 'revise'] as const)(
    'F003-AC01/06 F004-AC01 %s POST 경로·멱등 헤더·본문을 연결한다',
    async (mode) => {
      const inquiryId = crypto.randomUUID();
      const messageId = crypto.randomUUID();
      const create = { requestId: crypto.randomUUID(), content: 'VPN 문의' };
      const send = { ...create, inquiryId, expectedInquiryVersion: 1 };
      const revise = { ...send, messageId };
      const command = mode === 'create' ? create : mode === 'send' ? send : revise;
      const path =
        mode === 'create'
          ? '/api/inquiries'
          : `/api/inquiries/${inquiryId}/messages${mode === 'revise' ? `/${messageId}/revisions` : ''}`;
      rawBody = JSON.stringify({
        requestId: command.requestId,
        inquiryId,
        inquiryVersion: 1,
        messageId,
        revision: 1,
        runId: crypto.randomUUID(),
        effect: 'ANALYSIS_STARTED',
      });
      const outcome =
        mode === 'create'
          ? await createInquiry(create)
          : mode === 'send'
            ? await sendMessage(send)
            : await reviseMessage(revise);
      expect(outcome).toMatchObject({
        ok: true,
        requestId: command.requestId,
      });
      expect(responses[0]).toMatchObject({
        path,
        method: 'POST',
        idempotencyKey: command.requestId,
        body: JSON.stringify(command),
      });
    },
  );
  it('F003-AC04 저장 결과 식별자가 요청과 다르면 성공 처리하지 않는다', async () => {
    const command = { requestId: crypto.randomUUID(), content: '문의' };
    rawBody = JSON.stringify({
      requestId: crypto.randomUUID(),
      inquiryId: crypto.randomUUID(),
      inquiryVersion: 1,
      messageId: crypto.randomUUID(),
      revision: 1,
      runId: null,
      effect: 'SUPPLEMENT_SAVED',
    });
    expect(await postMutation('/api/inquiries', command)).toEqual({ ok: 'unknown' });
  });
  it('F003-AC04 401 후 인증을 갱신해도 저장 요청을 자동 재전송하지 않는다', async () => {
    useSession.getState().activate({ ...fixture('login-1', 'LoginResponse'), accessToken: token });
    const refresh = vi.fn(() => Promise.resolve(true));
    registerRefreshHandler(refresh);
    status = 401;
    rawBody = JSON.stringify({
      code: 'ACCESS_TOKEN_EXPIRED',
      message: '만료',
      requestId: crypto.randomUUID(),
      retryable: false,
    });
    const command = { requestId: crypto.randomUUID(), content: '문의' };
    expect(await postMutation('/api/inquiries', command)).toEqual({ ok: 'unknown' });
    expect(responses).toHaveLength(1);
    expect(refresh).toHaveBeenCalledOnce();
    registerRefreshHandler(() => Promise.resolve(false));
  });
  it('F003-AC04 HTTP 응답 본문 지연에도 timeout을 적용한다', async () => {
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
      apiRequest('/api/inquiries', {
        method: 'POST',
        idempotencyKey: crypto.randomUUID(),
        timeoutMs: 20,
      }),
    ).rejects.toMatchObject({ status: 0, body: { code: 'REQUEST_TIMEOUT' } });
  });
  it('F001-AC01·AC03·AC08 로그인 JSON → Bearer /me → 부서 티켓 조회 → 204 로그아웃을 연결한다', async () => {
    const credentials = { loginId: crypto.randomUUID(), password: crypto.randomUUID() };
    const user = await login(credentials);
    expect(user.id).toBe(fixture('user-1', 'CurrentUser').id);
    const list = await apiRequest<TicketPage>(
      '/api/tickets?page=0&size=20&processingStatus=WAITING',
    );
    expect(list.items).toHaveLength(1);
    const detail = await apiRequest<TicketDetail>(`/api/tickets/${list.items[0]?.id ?? ''}`);
    expect(detail.id).toBe(list.items[0]?.id);
    await logout();
    expect(responses[0]).toMatchObject({
      path: '/api/auth/login',
      method: 'POST',
      authorization: undefined,
      body: JSON.stringify(credentials),
    });
    expect(responses.slice(1).every((req) => req.authorization === `Bearer ${token}`)).toBe(true);
    expect(responses.at(-1)).toMatchObject({ method: 'POST', path: '/api/auth/logout', body: '' });
    expect(useSession.getState().token).toBeNull();
  });
  it('F003-AC05 문의·저장 메시지를 공통 REST URL로 조회한다', async () => {
    const inquiry = fixture('inquiry-result', 'InquiryResult');
    expect(await apiRequest<InquiryResult>(`/api/inquiries/${inquiry.id}`)).toEqual(inquiry);
    expect(
      await apiRequest<MessagePage>(
        `/api/inquiries/${inquiry.id}/messages?afterSequenceNo=0&size=20`,
      ),
    ).toEqual(fixture('messages', 'MessagePage'));
  });
  it.each([403, 404, 409, 503])('F009-AC06 서버의 %s 오류 계약을 보존한다', async (httpStatus) => {
    status = httpStatus;
    const body = {
      code: 'ACCESS_DENIED',
      message: '접근할 수 없어요.',
      requestId: crypto.randomUUID(),
      retryable: false,
    };
    rawBody = JSON.stringify(body);
    await expect(apiRequest('/api/tickets/denied')).rejects.toMatchObject({ status, body });
  });
  it('F001-AC05 401이면 현재 세션을 만료시킨다', async () => {
    await login({ loginId: crypto.randomUUID(), password: crypto.randomUUID() });
    status = 401;
    rawBody = JSON.stringify({
      code: 'UNAUTHENTICATED',
      message: '만료',
      requestId: crypto.randomUUID(),
      retryable: false,
    });
    await expect(apiRequest('/api/auth/me')).rejects.toMatchObject({ status: 401 });
    expect(useSession.getState().token).toBeNull();
  });
  it('F001-AC05 이전 토큰의 늦은 401이 새 계정을 만료시키지 않는다', async () => {
    const session = fixture('login-1', 'LoginResponse');
    useSession.getState().activate({ ...session, accessToken: token });
    status = 401;
    rawBody = JSON.stringify({
      code: 'UNAUTHENTICATED',
      message: '만료',
      requestId: crypto.randomUUID(),
      retryable: false,
    });
    await expect(apiRequest('/api/auth/me', { token: 'previous-session' })).rejects.toMatchObject({
      status: 401,
    });
    expect(useSession.getState().token).toBe(token);
  });
  it('F001-AC02 JSON이 아닌 응답과 네트워크 실패를 Mock 성공으로 대체하지 않는다', async () => {
    rawBody = '<html>error</html>';
    await expect(apiRequest('/api/auth/me')).rejects.toMatchObject({
      body: { code: 'INVALID_RESPONSE' },
    });
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('network')));
    await expect(apiRequest('/api/auth/me')).rejects.toBeInstanceOf(ApiError);
    await expect(apiRequest('/api/auth/me')).rejects.toMatchObject({
      body: { code: 'NETWORK_ERROR' },
    });
  });
  it('F009-AC03 취소 신호는 네트워크 오류로 바꾸지 않는다', async () => {
    const controller = new AbortController();
    controller.abort();
    await expect(apiRequest('/api/tickets', { signal: controller.signal })).rejects.toMatchObject({
      name: 'AbortError',
    });
  });
});
