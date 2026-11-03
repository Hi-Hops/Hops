import { afterEach, describe, expect, it, vi } from 'vitest';
import { TEST_LOGINS } from '@/test/mockCredentials';
import { SystemMockBackend } from './systemMockBackend';
import { systems, users } from './systemMockData';
import { readLogin, readTickets, readUser } from './systemWire';

afterEach(() => {
  vi.unstubAllEnvs();
});

function signIn(backend: SystemMockBackend) {
  return readLogin(backend.request('/api/v2/auth/login', 'POST', TEST_LOGINS.network, null));
}

function previewSignIn(backend: SystemMockBackend) {
  const loginId = crypto.randomUUID();
  vi.stubEnv('VITE_MOCK_PREVIEW_ID', loginId);
  return readLogin(
    backend.request(
      '/api/v2/auth/login',
      'POST',
      { loginId, password: TEST_LOGINS.network.password },
      null,
    ),
  );
}

describe('F001/F007 Mock 체험 권한 격리', () => {
  it('F001-AC06/F007-CONTRACT-01 체험 로그인은 기존·후속 일반 담당자 세션에 관리자 권한을 전파하지 않는다', () => {
    const backend = new SystemMockBackend();
    const staff = signIn(backend);
    const preview = previewSignIn(backend);
    expect(preview.user.roles).toEqual(['BIZ_ADMIN']);
    expect(preview.user.primarySystemIds).toEqual(users[1].primarySystemIds);

    const existing = readUser(
      backend.request('/api/v2/auth/me', 'GET', undefined, staff.accessToken),
    );
    expect(existing.roles).toEqual([]);
    expect(existing.primarySystemIds).toEqual(users[1].primarySystemIds);
    expect(() =>
      backend.request('/api/v2/tickets?scope=ADMIN', 'GET', undefined, staff.accessToken),
    ).toThrow(expect.objectContaining({ status: 403 }));

    backend.request('/api/v2/auth/logout', 'POST', undefined, preview.accessToken);
    vi.stubEnv('VITE_MOCK_PREVIEW_ID', '');
    const nextStaff = signIn(backend);
    expect(nextStaff.user.roles).toEqual([]);
    expect(nextStaff.user.primarySystemIds).toEqual(users[1].primarySystemIds);
    expect(() =>
      backend.request('/api/v2/tickets?scope=ADMIN', 'GET', undefined, nextStaff.accessToken),
    ).toThrow(expect.objectContaining({ status: 403 }));
  });

  it('F001-AC05 체험 갱신은 해당 세션의 관리자 권한과 최신 주담당 관계를 유지한다', () => {
    const backend = new SystemMockBackend();
    const preview = previewSignIn(backend);
    backend.expire(preview.accessToken);
    const refreshed = readLogin(backend.request('/api/v2/auth/refresh', 'POST', undefined, null));
    expect(refreshed.user.roles).toEqual(['BIZ_ADMIN']);
    expect(refreshed.user.primarySystemIds).toEqual(users[1].primarySystemIds);
    const tickets = readTickets(
      backend.request('/api/v2/tickets?scope=ADMIN', 'GET', undefined, refreshed.accessToken),
    );
    expect(tickets.totalElements).toBe(2);

    backend.revokeSystem(users[1].id, systems[0].id);
    const latest = readUser(
      backend.request('/api/v2/auth/me', 'GET', undefined, refreshed.accessToken),
    );
    expect(latest.primarySystemIds).toEqual([systems[1].id]);
    expect(latest.roles).toEqual(['BIZ_ADMIN']);
  });
});
