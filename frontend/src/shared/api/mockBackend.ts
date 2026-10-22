import accounts from '@fixtures/accounts.json';
import type {
  ApiErrorBody,
  CurrentUser,
  LoginRequest,
  LoginResponse,
  SubscriptionTarget,
} from './types';
import { fixture } from './mockData';

export class MockHttpError extends Error {
  constructor(
    readonly status: number,
    readonly body: ApiErrorBody,
  ) {
    super(body.message);
    this.name = 'MockHttpError';
  }
}

export class MockBackend {
  private sessions = new Map<string, { user: CurrentUser; expiresAt: number }>();
  private nextFailure: number | null = null;

  failNext(status = 503) {
    this.nextFailure = status;
  }
  reset() {
    this.sessions.clear();
    this.nextFailure = null;
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

  authorizeTarget(token: string, target: SubscriptionTarget) {
    const user = this.currentUser(token);
    const requester = fixture('user-0', 'CurrentUser');
    if (
      target.type === 'INQUIRY' &&
      user.id === requester.id &&
      target.id === fixture('inquiry-result', 'InquiryResult').id
    )
      return;
    if (
      target.type === 'DEPARTMENT_TICKETS' &&
      ((user.department.isRoutable && target.id === user.department.id) ||
        user.roles.includes('BIZ_ADMIN'))
    )
      return;
    if (target.type === 'TICKET') {
      const tickets = fixture('inquiry-result', 'InquiryResult').tickets;
      if (
        tickets.some(
          (ticket) =>
            ticket.id === target.id &&
            (ticket.department.id === user.department.id || user.roles.includes('BIZ_ADMIN')),
        )
      )
        return;
    }
    this.error(403, 'ACCESS_DENIED', '이 정보에 접근할 권한이 없어요.');
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
      const account = accounts.accounts.find((item) => item.loginId === credentials.loginId);
      if (!account || credentials.password !== accounts.password)
        this.error(401, 'UNAUTHENTICATED', '아이디 또는 비밀번호를 확인해 주세요.');
      const user = fixture(account.userFixture, 'CurrentUser');
      const expiresAt = Date.now() + 1_800_000;
      const accessToken = `mock:${crypto.randomUUID()}`;
      this.sessions.set(accessToken, { user, expiresAt });
      return {
        accessToken,
        expiresAt: new Date(expiresAt).toISOString(),
        user,
      } satisfies LoginResponse;
    }
    const user = this.currentUser(token);
    if (path === '/api/auth/me') return user;
    if (path === '/api/auth/logout' && method === 'POST') {
      if (token) this.sessions.delete(token);
      return undefined;
    }
    this.error(404, 'NOT_FOUND', '요청한 정보를 찾을 수 없어요.');
  }
}

export const mockBackend = new MockBackend();
