import { beforeEach, describe, expect, it, vi } from 'vitest';
import { mockBackend } from '@/shared/api/mockBackend';
import { fixture } from '@/shared/api/mockData';
import { TEST_LOGINS } from '@/test/mockCredentials';
import { readMockAuth } from './mockAuth';
beforeEach(() => {
  mockBackend.reset();
});
describe('F001 환경 변수 Mock 로그인', () => {
  it.each([
    ['requester', 'user-0'],
    ['network', 'user-1'],
    ['backend', 'user-2'],
    ['admin', 'user-3'],
  ] as const)('F001-AC01 %s의 환경 값으로 해당 사용자에 로그인한다', (role, id) => {
    expect(mockBackend.request('/api/auth/login', 'POST', TEST_LOGINS[role], null)).toEqual(
      expect.objectContaining({ user: fixture(id, 'CurrentUser') }),
    );
  });
  it('F001-AC01 환경 값을 바꾸면 새 값만 적용되고 이전 값은 거부된다', () => {
    const loginId = crypto.randomUUID();
    const password = crypto.randomUUID();
    vi.stubEnv('VITE_MOCK_REQUESTER_ID', loginId);
    vi.stubEnv('VITE_MOCK_PASSWORD', password);
    expect(mockBackend.request('/api/auth/login', 'POST', { loginId, password }, null)).toEqual(
      expect.objectContaining({ user: fixture('user-0', 'CurrentUser') }),
    );
    expect(() =>
      mockBackend.request('/api/auth/login', 'POST', TEST_LOGINS.requester, null),
    ).toThrow('아이디 또는 비밀번호');
  });
  it('F001-AC02 누락 설정은 기본 로그인 값으로 대체하지 않는다', () => {
    vi.stubEnv('VITE_MOCK_PASSWORD', '');
    expect(readMockAuth()).toBeNull();
    expect(() =>
      mockBackend.request('/api/auth/login', 'POST', TEST_LOGINS.requester, null),
    ).toThrow('Mock 로그인 설정');
  });
  it('F001-AC02 중복 아이디는 서로 다른 권한의 계정으로 허용하지 않는다', () => {
    vi.stubEnv('VITE_MOCK_ADMIN_ID', TEST_LOGINS.requester.loginId);
    expect(readMockAuth()).toBeNull();
  });
  it('F001-AC02 아이디가 없거나 비밀번호가 공백뿐이면 설정 오류다', () => {
    vi.stubEnv('VITE_MOCK_BACKEND_ID', '');
    expect(readMockAuth()).toBeNull();
    vi.stubEnv('VITE_MOCK_BACKEND_ID', TEST_LOGINS.backend.loginId);
    vi.stubEnv('VITE_MOCK_PASSWORD', '   ');
    expect(readMockAuth()).toBeNull();
  });
});
