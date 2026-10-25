import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiRequest, ApiError } from '@/shared/api/client';
import { mockBackend, MockHttpError } from '@/shared/api/mockBackend';
import { TEST_LOGINS } from '@/test/mockCredentials';
import { login } from '../api/authApi';
import { refreshSession, useSession } from './sessionStore';
beforeEach(async () => {
  mockBackend.reset();
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
  it('F001-AC04 서버가 부서 변경을 알리면 refresh 반복 없이 재로그인한다', async () => {
    const original = mockBackend.request.bind(mockBackend);
    const request = vi.spyOn(mockBackend, 'request').mockImplementation((path, ...args) => {
      if (path === '/api/auth/me')
        throw new MockHttpError(403, {
          code: 'AUTH_CONTEXT_CHANGED',
          message: '부서 변경',
          requestId: crypto.randomUUID(),
          retryable: false,
        });
      return original(path, ...args);
    });
    await expect(apiRequest('/api/auth/me')).rejects.toMatchObject({ status: 403 });
    expect(useSession.getState().token).toBeNull();
    expect(request.mock.calls.some(([path]) => path === '/api/auth/refresh')).toBe(false);
  });
  it('F001-AC05 live refresh만 쿠키를 전달하고 업무 조회는 새 Bearer로 한 번 복구한다', async () => {
    const previous = token();
    const user = useSession.getState().user;
    vi.stubEnv('VITE_DATA_MODE', 'live');
    vi.stubEnv('VITE_API_BASE_URL', 'http://localhost:8080');
    vi.stubEnv('VITE_SOCKET_URL', 'http://localhost:3001');
    const request = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            code: 'UNAUTHENTICATED',
            message: '만료',
            requestId: crypto.randomUUID(),
            retryable: false,
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
    await apiRequest('/api/auth/me');
    expect(request.mock.calls[1]?.[0]).toBe('http://localhost:8080/api/auth/refresh');
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
    vi.stubEnv('VITE_SOCKET_URL', 'http://localhost:3001');
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
    mockBackend.expire(previous);
    const request = vi.spyOn(mockBackend, 'request');
    await Promise.all([apiRequest('/api/auth/me'), apiRequest('/api/auth/me')]);
    expect(token()).not.toBe(previous);
    expect(request.mock.calls.filter(([path]) => path === '/api/auth/refresh')).toHaveLength(1);
    expect(useSession.getState().user).not.toBeNull();
  });
  it('F001-AC05 갱신 토큰 만료는 재로그인을 요청하고 이전 사용자 식별자를 보존한다', async () => {
    const previous = token();
    mockBackend.expire(previous);
    mockBackend.expireRefresh();
    await expect(apiRequest('/api/auth/me')).rejects.toBeInstanceOf(ApiError);
    expect(useSession.getState().token).toBeNull();
    expect(useSession.getState().previousUserId).not.toBeNull();
  });
  it('F001-AC05 갱신 통신 장애는 로그인 만료로 바꾸지 않는다', async () => {
    const previous = token();
    mockBackend.failNext(503);
    await expect(refreshSession(previous)).rejects.toBeInstanceOf(ApiError);
    expect(token()).toBe(previous);
  });
  it('F001-AC04 권한 거부 403에는 갱신이나 재로그인을 반복하지 않는다', async () => {
    const previous = token();
    const original = mockBackend.request.bind(mockBackend);
    const request = vi.spyOn(mockBackend, 'request').mockImplementation((path, ...args) => {
      if (path === '/api/tickets')
        throw new MockHttpError(403, {
          code: 'ACCESS_DENIED',
          message: '권한 없음',
          requestId: crypto.randomUUID(),
          retryable: false,
        });
      return original(path, ...args);
    });
    await expect(apiRequest('/api/tickets')).rejects.toMatchObject({ status: 403 });
    expect(token()).toBe(previous);
    expect(request.mock.calls.some(([path]) => path === '/api/auth/refresh')).toBe(false);
  });
});
