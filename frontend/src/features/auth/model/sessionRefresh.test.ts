import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiRequest, ApiError } from '@/shared/api/client';
import { systemMockBackend } from '@/shared/api/systemMockBackend';
import { MockHttpError } from '@/shared/api/mockError';
import { TEST_LOGINS } from '@/test/mockCredentials';
import { login } from '../api/authApi';
import { REFRESH_LOCK_NAME, refreshSession, restoreSession, useSession } from './sessionStore';
beforeEach(async () => {
  systemMockBackend.reset();
  useSession.getState().logout();
  await login(TEST_LOGINS.requester);
});
afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});
function token() {
  const value = useSession.getState().token;
  if (!value) throw new Error('토큰 필요');
  return value;
}
describe('F001 Refresh Token 갱신', () => {
  it('F001-AC04 서버가 권한 변경을 알리면 refresh 반복 없이 재로그인한다', async () => {
    const original = systemMockBackend.request.bind(systemMockBackend);
    const request = vi.spyOn(systemMockBackend, 'request').mockImplementation((path, ...args) => {
      if (path === '/api/v2/auth/me')
        throw new MockHttpError(403, {
          code: 'AUTH_CONTEXT_CHANGED',
          message: '담당 시스템 변경',
          requestId: crypto.randomUUID(),
          retryable: false,
          fieldErrors: [],
        });
      return original(path, ...args);
    });
    await expect(apiRequest('/api/v2/auth/me')).rejects.toMatchObject({ status: 403 });
    expect(useSession.getState().token).toBeNull();
    expect(request.mock.calls.some(([path]) => path === '/api/v2/auth/refresh')).toBe(false);
  });
  it('F001-AC05 live refresh만 쿠키를 전달하고 업무 조회는 새 Bearer로 한 번 복구한다', async () => {
    const previous = token();
    const user = useSession.getState().user;
    vi.stubEnv('VITE_DATA_MODE', 'live');
    vi.stubEnv('VITE_API_BASE_URL', 'http://localhost:8080');
    const request = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            code: 'ACCESS_TOKEN_EXPIRED',
            message: '만료',
            requestId: crypto.randomUUID(),
            retryable: false,
            fieldErrors: [],
          }),
          { status: 401 },
        ),
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            accessToken: 'new-access',
            expiresAt: new Date(Date.now() + 60000).toISOString(),
            user,
          }),
        ),
      )
      .mockResolvedValueOnce(new Response(JSON.stringify(user)));
    vi.stubGlobal('fetch', request);
    await apiRequest('/api/v2/auth/me');
    expect(request.mock.calls[1]?.[0]).toBe('http://localhost:8080/api/v2/auth/refresh');
    expect(request.mock.calls[1]?.[1]?.credentials).toBe('include');
    expect(request.mock.calls[1]?.[1]?.headers).not.toHaveProperty('Authorization');
    expect(request.mock.calls[2]?.[1]).toMatchObject({
      credentials: 'omit',
      headers: { Authorization: 'Bearer new-access' },
    });
    expect(token()).not.toBe(previous);
  });
  it('F001-AC05 로그아웃 뒤 도착한 refresh는 세션을 되살리지 않는다', async () => {
    const previous = token();
    const user = useSession.getState().user;
    vi.stubEnv('VITE_DATA_MODE', 'live');
    vi.stubEnv('VITE_API_BASE_URL', 'http://localhost:8080');
    let finish: ((value: Response) => void) | undefined;
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>().mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finish = resolve;
          }),
      ),
    );
    const refresh = refreshSession(previous);
    useSession.getState().logout();
    finish?.(
      new Response(
        JSON.stringify({ accessToken: 'late-token', expiresAt: new Date().toISOString(), user }),
      ),
    );
    await expect(refresh).resolves.toBe(false);
    expect(useSession.getState().token).toBeNull();
  });
  it('F001-AC05 동시 401은 한 번 갱신하고 새 토큰으로 원래 조회를 복구한다', async () => {
    const previous = token();
    systemMockBackend.expire(previous);
    const request = vi.spyOn(systemMockBackend, 'request');
    await Promise.all([apiRequest('/api/v2/auth/me'), apiRequest('/api/v2/auth/me')]);
    expect(token()).not.toBe(previous);
    expect(request.mock.calls.filter(([path]) => path === '/api/v2/auth/refresh')).toHaveLength(1);
    expect(useSession.getState().user).not.toBeNull();
  });
  it('F001-AC05 갱신 토큰 만료는 재로그인을 요청하고 이전 사용자 식별자를 보존한다', async () => {
    const previous = token();
    systemMockBackend.expire(previous);
    systemMockBackend.expireRefresh();
    await expect(apiRequest('/api/v2/auth/me')).rejects.toBeInstanceOf(ApiError);
    expect(useSession.getState().token).toBeNull();
    expect(useSession.getState().previousUserId).not.toBeNull();
  });
  it('F001-AC05 갱신 통신 장애는 로그인 만료로 바꾸지 않는다', async () => {
    const previous = token();
    systemMockBackend.failNext(503);
    await expect(refreshSession(previous)).rejects.toBeInstanceOf(ApiError);
    expect(token()).toBe(previous);
  });
  it('F001-AC04 권한 거부 403에는 갱신이나 재로그인을 반복하지 않는다', async () => {
    const previous = token();
    const original = systemMockBackend.request.bind(systemMockBackend);
    const request = vi.spyOn(systemMockBackend, 'request').mockImplementation((path, ...args) => {
      if (path === '/api/v2/tickets')
        throw new MockHttpError(403, {
          code: 'ACCESS_DENIED',
          message: '권한 없음',
          requestId: crypto.randomUUID(),
          retryable: false,
          fieldErrors: [],
        });
      return original(path, ...args);
    });
    await expect(apiRequest('/api/v2/tickets')).rejects.toMatchObject({ status: 403 });
    expect(token()).toBe(previous);
    expect(request.mock.calls.some(([path]) => path === '/api/v2/auth/refresh')).toBe(false);
  });
});
describe('F001 새로고침 세션 복원', () => {
  it('F001-AC05 live 복원은 Bearer 없이 Refresh 쿠키만 전달해 세션을 되살린다', async () => {
    const user = useSession.getState().user;
    useSession.setState({
      user: null,
      token: null,
      expiresAt: null,
      previousUserId: null,
      restoreStatus: 'pending',
    });
    vi.stubEnv('VITE_DATA_MODE', 'live');
    vi.stubEnv('VITE_API_BASE_URL', 'http://localhost:8080');
    const request = vi.fn<typeof fetch>().mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          accessToken: 'restored-access',
          expiresAt: new Date(Date.now() + 60000).toISOString(),
          user,
        }),
      ),
    );
    vi.stubGlobal('fetch', request);
    await restoreSession();
    expect(request.mock.calls[0]?.[0]).toBe('http://localhost:8080/api/v2/auth/refresh');
    expect(request.mock.calls[0]?.[1]).toMatchObject({ method: 'POST', credentials: 'include' });
    expect(request.mock.calls[0]?.[1]?.headers).not.toHaveProperty('Authorization');
    expect(useSession.getState()).toMatchObject({
      token: 'restored-access',
      user,
      restoreStatus: 'settled',
    });
  });
  it('F001-AC05 복원 중 로그아웃하면 늦게 온 복원 결과로 세션을 되살리지 않는다', async () => {
    useSession.setState({ user: null, token: null, expiresAt: null, restoreStatus: 'pending' });
    const pending = restoreSession();
    useSession.getState().logout();
    await pending;
    expect(useSession.getState()).toMatchObject({
      user: null,
      token: null,
      restoreStatus: 'settled',
    });
  });
  it('F001-AC05 다른 탭이 먼저 회전한 Refresh 거절은 한 번 다시 요청해 복원한다', async () => {
    useSession.setState({ user: null, token: null, expiresAt: null, restoreStatus: 'pending' });
    const request = revokeFirstRefresh();
    await restoreSession();
    expect(useSession.getState()).toMatchObject({ restoreStatus: 'settled' });
    expect(useSession.getState().token).not.toBeNull();
    expect(refreshCalls(request)).toBe(2);
  });
  it('F001-AC05 다시 요청해도 폐기된 Refresh는 로그인으로 보내고 반복하지 않는다', async () => {
    useSession.getState().logout();
    useSession.setState({ restoreStatus: 'pending' });
    const request = vi.spyOn(systemMockBackend, 'request').mockImplementation(() => {
      throw revoked();
    });
    await restoreSession();
    expect(useSession.getState()).toMatchObject({ user: null, restoreStatus: 'settled' });
    expect(refreshCalls(request)).toBe(2);
  });
  it('F001-AC05 탭 간 만료 갱신 경합도 로그인 만료로 바꾸지 않는다', async () => {
    const previous = token();
    const request = revokeFirstRefresh();
    await expect(refreshSession(previous)).resolves.toBe(true);
    expect(token()).not.toBe(previous);
    expect(refreshCalls(request)).toBe(2);
  });
  it('F001-AC05 탭 사이 refresh는 같은 Web Lock 안에서 한 번에 하나씩 보낸다', async () => {
    const order: string[] = [];
    const request = vi.fn(async (name: string, task: () => Promise<unknown>) => {
      order.push(`lock:${name}`);
      const result = await task();
      order.push('unlock');
      return result;
    });
    Object.defineProperty(navigator, 'locks', { value: { request }, configurable: true });
    try {
      await expect(refreshSession(token())).resolves.toBe(true);
      expect(order).toEqual([`lock:${REFRESH_LOCK_NAME}`, 'unlock']);
    } finally {
      Reflect.deleteProperty(navigator, 'locks');
    }
  });
});
function revoked() {
  return new MockHttpError(401, {
    code: 'REFRESH_TOKEN_REVOKED',
    message: '폐기',
    requestId: crypto.randomUUID(),
    retryable: false,
    fieldErrors: [],
  });
}
/** 같은 Refresh로 먼저 갱신한 다른 탭이 있는 상황: 첫 refresh만 거절된다. */
function revokeFirstRefresh() {
  const original = systemMockBackend.request.bind(systemMockBackend);
  let rejected = false;
  return vi.spyOn(systemMockBackend, 'request').mockImplementation((path, ...args) => {
    if (path === '/api/v2/auth/refresh' && !rejected) {
      rejected = true;
      throw revoked();
    }
    return original(path, ...args);
  });
}
function refreshCalls(request: { mock: { calls: unknown[][] } }) {
  return request.mock.calls.filter(([path]) => path === '/api/v2/auth/refresh').length;
}
