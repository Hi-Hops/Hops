import { TEST_LOGINS } from '@/test/mockCredentials';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithProviders } from '@/test/renderWithProviders';
import { mockBackend } from '@/shared/api/mockBackend';
import { ApiError, apiRequest } from '@/shared/api/client';
import { fixture } from '@/shared/api/mockData';
import { onSessionEvent } from '@/shared/lib/sessionEvents';
import { LoginForm } from './LoginForm';
import { useSession } from '../model/sessionStore';
import { login, logout } from '../api/authApi';

beforeEach(() => {
  mockBackend.reset();
  useSession.getState().logout();
  vi.restoreAllMocks();
});

async function fillLogin(password: string = TEST_LOGINS.requester.password) {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText('아이디'), TEST_LOGINS.requester.loginId);
  await user.type(screen.getByLabelText('비밀번호'), password);
  await user.click(screen.getByRole('button', { name: '로그인' }));
}

describe('F001 로그인', () => {
  it('F001-AC01 로그인 후 검증된 사용자 정보를 받는다', async () => {
    const success = vi.fn();
    renderWithProviders(<LoginForm onSuccess={success} />);
    await fillLogin();
    await waitFor(() => {
      expect(success).toHaveBeenCalledWith(fixture('user-0', 'CurrentUser'));
    });
    expect(useSession.getState().user?.department.name).toBe('가상 업무부서');
  });
  it('F001-AC02 실패하면 아이디는 유지하고 비밀번호는 지운다', async () => {
    renderWithProviders(<LoginForm onSuccess={vi.fn()} />);
    await fillLogin('wrong-password');
    expect(await screen.findByRole('alert')).toHaveTextContent('아이디 또는 비밀번호');
    expect(screen.getByLabelText('아이디')).toHaveValue(TEST_LOGINS.requester.loginId);
    expect(screen.getByLabelText('비밀번호')).toHaveValue('');
  });
  it('F001-AC03 토큰 디코딩 대신 /auth/me를 조회한다', async () => {
    const spy = vi.spyOn(mockBackend, 'request');
    const current = await login(TEST_LOGINS.network);
    expect(spy).toHaveBeenCalledWith('/api/auth/me', 'GET', undefined, expect.any(String));
    expect(current.department.code).toBe('NETWORK');
  });
  it('F001-AC05 인증 만료는 재로그인 안내로 표시한다', () => {
    renderWithProviders(<LoginForm expired onSuccess={vi.fn()} />);
    expect(screen.getByRole('status')).toHaveTextContent('이미 접수된 작업은 계속 진행');
  });
  it('F001-AC06 다른 계정 로그인 시 사용자 전용 상태 정리 신호를 보낸다', () => {
    const listener = vi.fn();
    const off = onSessionEvent(listener);
    useSession.getState().activate(fixture('login-0', 'LoginResponse'));
    useSession.getState().expire();
    useSession.getState().activate(fixture('login-1', 'LoginResponse'));
    expect(listener).toHaveBeenCalledWith('switched');
    off();
  });
  it('F001-AC06 같은 계정 재로그인에서는 전환 정리를 하지 않는다', () => {
    useSession.getState().activate(fixture('login-0', 'LoginResponse'));
    useSession.getState().expire();
    const listener = vi.fn();
    const off = onSessionEvent(listener);
    useSession.getState().activate(fixture('login-0', 'LoginResponse'));
    expect(listener).not.toHaveBeenCalledWith('switched');
    off();
  });
  it('F001-AC07 인증 만료는 작업 취소가 아니라 인증 상태만 변경한다', async () => {
    await login(TEST_LOGINS.requester);
    const token = useSession.getState().token;
    if (!token) throw new Error('테스트 로그인 실패');
    mockBackend.expire(token);
    await expect(apiRequest('/api/auth/me')).rejects.toBeInstanceOf(ApiError);
    expect(useSession.getState().user).toBeNull();
    expect(useSession.getState().previousUserId).toBe(fixture('user-0', 'CurrentUser').id);
  });
  it('F001-AC08 로그아웃 요청 실패 시에도 인증 정보를 정리한다', async () => {
    await login(TEST_LOGINS.requester);
    mockBackend.failNext();
    await expect(logout()).rejects.toBeInstanceOf(ApiError);
    expect(useSession.getState().token).toBeNull();
    expect(useSession.getState().previousUserId).toBeNull();
  });
  it('F001-AC09 화면에 토큰을 노출하지 않고 비밀번호 입력을 가린다', async () => {
    renderWithProviders(<LoginForm onSuccess={vi.fn()} />);
    await fillLogin();
    await waitFor(() => {
      expect(useSession.getState().token).not.toBeNull();
    });
    expect(screen.getByLabelText('비밀번호')).toHaveAttribute('type', 'password');
    const token = useSession.getState().token;
    expect(document.body.textContent).not.toContain(token);
  });
  it('F001-AC02 일시 서버 오류를 로그인 정보 오류와 구분한다', async () => {
    renderWithProviders(<LoginForm onSuccess={vi.fn()} />);
    mockBackend.failNext();
    await fillLogin();
    expect(await screen.findByRole('alert')).toHaveTextContent('서버에 연결하지 못했어요');
  });
});
