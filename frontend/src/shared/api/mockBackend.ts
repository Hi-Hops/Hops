import { readMockAuth } from '@/shared/config/mockAuth';
import type {
  CurrentUser,
  LoginRequest,
  LoginResponse,
  SubscriptionTarget,
  TicketPage,
  RunRetryRequest,
} from './types';
import { fixture } from './mockData';
import { MockHttpError } from './mockError';
import { mockWorkspace } from './mockWorkspace';
import type { StoredCommand, StoredCommandName } from '@/shared/realtime/types';
export { MockHttpError } from './mockError';

export class MockBackend {
  private sessions = new Map<string, { user: CurrentUser; expiresAt: number }>();
  private nextFailure: number | null = null;
  private refresh: { user: CurrentUser; expiresAt: number } | null = null;

  failNext(status = 503) {
    this.nextFailure = status;
  }
  reset() {
    this.sessions.clear();
    this.refresh = null;
    this.nextFailure = null;
    mockWorkspace.reset();
  }

  error(status: number, code: string, message: string): never {
    throw new MockHttpError(status, {
      code,
      message,
      requestId: crypto.randomUUID(),
      retryable: status === 503,
    });
  }

  currentUser(token: string | null): CurrentUser {
    const session = token ? this.sessions.get(token) : undefined;
    if (!session || session.expiresAt <= Date.now())
      this.error(401, 'UNAUTHENTICATED', '로그인이 만료됐어요. 다시 로그인해 주세요.');
    return structuredClone(session.user);
  }

  expire(token: string) {
    const session = this.sessions.get(token);
    if (session) session.expiresAt = 0;
  }
  expireRefresh() {
    this.refresh = null;
  }

  authorizeTarget(token: string, target: SubscriptionTarget) {
    mockWorkspace.authorize(this.currentUser(token), target);
  }

  command(name: StoredCommandName, command: StoredCommand, token: string) {
    return mockWorkspace.command(this.currentUser(token), name, command);
  }

  request(path: string, method: string, body: unknown, token: string | null): unknown {
    if (this.nextFailure !== null) {
      const status = this.nextFailure;
      this.nextFailure = null;
      this.error(status, 'SERVICE_UNAVAILABLE', '서버에 연결하지 못했어요. 다시 시도해 주세요.');
    }
    if (path === '/api/auth/login' && method === 'POST') {
      // HTTP 대역 경계: 호출부의 공통 LoginRequest를 해석한다. 실제 인증 구현은 아니다.
      const credentials = body as LoginRequest;
      const config = readMockAuth();
      if (!config)
        this.error(
          503,
          'MOCK_AUTH_NOT_CONFIGURED',
          'Mock 로그인 설정이 없거나 올바르지 않아요. .env의 계정 값을 확인하고 실행 서버를 다시 시작해 주세요.',
        );
      const account = config.accounts.find((item) => item.loginId === credentials.loginId);
      if (!account || credentials.password !== config.password)
        this.error(401, 'UNAUTHENTICATED', '아이디 또는 비밀번호를 확인해 주세요.');
      const user = fixture(account.userFixture, 'CurrentUser');
      const expiresAt = Date.now() + 1_800_000;
      const accessToken = `mock:${crypto.randomUUID()}`;
      this.sessions.set(accessToken, { user, expiresAt });
      this.refresh = { user, expiresAt: Date.now() + 7 * 24 * 60 * 60 * 1000 };
      return {
        accessToken,
        expiresAt: new Date(expiresAt).toISOString(),
        user,
      } satisfies LoginResponse;
    }
    if (path === '/api/auth/refresh' && method === 'POST') {
      if (!this.refresh || this.refresh.expiresAt <= Date.now())
        this.error(401, 'REFRESH_TOKEN_EXPIRED', '인증이 만료됐어요. 다시 로그인해 주세요.');
      const user = structuredClone(this.refresh.user);
      const expiresAt = Date.now() + 1_800_000;
      const accessToken = `mock:${crypto.randomUUID()}`;
      this.sessions.set(accessToken, { user, expiresAt });
      return {
        accessToken,
        expiresAt: new Date(expiresAt).toISOString(),
        user,
      } satisfies LoginResponse;
    }
    if (path === '/api/auth/logout' && method === 'POST') {
      this.refresh = null;
      if (token) this.sessions.delete(token);
      return undefined;
    }
    const user = this.currentUser(token);
    if (path === '/api/auth/me') return user;
    const url = new URL(path, 'http://mock.invalid');
    const readNumber = (key: string, fallback: number, max: number) => {
      const raw = url.searchParams.get(key);
      if (raw === null) return fallback;
      if (!/^\d+$/u.test(raw) || Number(raw) > max)
        this.error(400, 'INVALID_INPUT', '조회 조건을 확인해 주세요.');
      return Number(raw);
    };
    const page = readNumber('page', 0, 2147483647);
    const size = readNumber('size', 20, 100);
    if (size < 1) this.error(400, 'INVALID_INPUT', '페이지 크기를 확인해 주세요.');
    if (url.pathname === '/api/inquiries') return mockWorkspace.listInquiries(user, page, size);
    if (
      (url.searchParams.has('afterSequenceNo') && url.searchParams.has('beforeSequenceNo')) ||
      url.searchParams.get('beforeSequenceNo') === '0'
    )
      this.error(400, 'INVALID_INPUT', '메시지 커서를 확인해 주세요.');
    const inquiryMatch = /^\/api\/inquiries\/([^/]+)(\/messages)?$/u.exec(url.pathname);
    if (inquiryMatch?.[1])
      return inquiryMatch[2]
        ? url.searchParams.has('afterSequenceNo')
          ? mockWorkspace.messages(
              user,
              inquiryMatch[1],
              readNumber('afterSequenceNo', 0, 2147483647),
              size,
            )
          : mockWorkspace.history(
              user,
              inquiryMatch[1],
              url.searchParams.has('beforeSequenceNo')
                ? readNumber('beforeSequenceNo', 0, 2147483647)
                : null,
              size,
            )
        : mockWorkspace.readInquiry(user, inquiryMatch[1]);
    if (url.pathname === '/api/tickets') {
      if (!user.department.isRoutable && !user.roles.includes('BIZ_ADMIN'))
        this.error(403, 'ACCESS_DENIED', '부서 티켓 조회 권한이 없어요.');
      const status = url.searchParams.get('processingStatus');
      if (
        status &&
        !['WAITING', 'IN_PROGRESS', 'WAITING_CONFIRM', 'RESOLVED', 'CLOSED_UNCONFIRMED'].includes(
          status,
        )
      )
        this.error(400, 'INVALID_INPUT', '처리 상태를 확인해 주세요.');
      const tickets = mockWorkspace
        .tickets(user)
        .filter((ticket) => !status || ticket.processingStatus === status);
      return {
        items: tickets.slice(page * size, (page + 1) * size),
        page,
        size,
        totalElements: tickets.length,
      } satisfies TicketPage;
    }
    const ticketMatch = /^\/api\/tickets\/([^/]+)$/u.exec(url.pathname);
    if (ticketMatch?.[1]) return mockWorkspace.ticket(user, ticketMatch[1]);
    const retryMatch = /^\/api\/runs\/([^/]+)\/retries$/u.exec(url.pathname);
    if (retryMatch?.[1] && method === 'POST') {
      const request = body as RunRetryRequest;
      if (
        typeof body !== 'object' ||
        body === null ||
        typeof request.requestId !== 'string' ||
        !request.requestId ||
        !Number.isSafeInteger(request.expectedInquiryVersion) ||
        request.expectedInquiryVersion < 1
      )
        this.error(400, 'INVALID_INPUT', '재시도 요청을 확인해 주세요.');
      return mockWorkspace.retryRun(user, retryMatch[1], request);
    }
    const runMatch = /^\/api\/runs\/([^/]+)$/u.exec(url.pathname);
    if (runMatch?.[1]) return mockWorkspace.readRun(user, runMatch[1]);
    this.error(404, 'NOT_FOUND', '요청한 정보를 찾을 수 없어요.');
  }
}

export const mockBackend = new MockBackend();
