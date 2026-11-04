import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { sessionFor } from '@/test/systemFixtures';
import { TEST_LOGINS } from '@/test/mockCredentials';
import { login, useSession } from '@/features/auth';
import { systemMockBackend } from '@/shared/api/systemMockBackend';
import { MockHttpError } from '@/shared/api/mockError';
import { RequireAuth } from './RequireAuth';
import { RequireRole } from './RequireRole';

beforeEach(() => {
  systemMockBackend.reset();
  useSession.getState().logout();
});
afterEach(() => {
  vi.restoreAllMocks();
});
/** 새로고침하면 메모리 세션만 사라지고 Refresh 쿠키(Mock은 메모리 refresh)는 남는다. */
function reload() {
  useSession.setState({
    user: null,
    token: null,
    expiresAt: null,
    previousUserId: null,
    restoreStatus: 'pending',
  });
}
function renderProtected() {
  const router = createMemoryRouter(
    [
      {
        element: <RequireAuth />,
        children: [{ path: '/inquiries/:id/chat', element: <h1>채팅</h1> }],
      },
      { path: '/login', element: <h1>로그인</h1> },
    ],
    { initialEntries: ['/inquiries/1/chat'] },
  );
  render(<RouterProvider router={router} />);
  return router;
}
describe('F001 경로 권한', () => {
  it('F001-AC04 일반 문의자에게 관리자 내용을 표시하지 않는다', async () => {
    useSession.getState().activate(sessionFor('requester'));
    const router = createMemoryRouter(
      [
        {
          element: <RequireRole role="BIZ_ADMIN" />,
          children: [{ path: '/admin', element: <h1>보호된 관리 내용</h1> }],
        },
        { path: '/403', element: <h1>권한 없음</h1> },
      ],
      { initialEntries: ['/admin'] },
    );
    render(<RouterProvider router={router} />);
    expect(await screen.findByRole('heading', { name: '권한 없음' })).toBeVisible();
    expect(screen.queryByText('보호된 관리 내용')).not.toBeInTheDocument();
  });
  it('F001-AC05 비로그인 방문의 원래 경로를 재로그인 상태에 보존한다', async () => {
    const router = createMemoryRouter(
      [
        { element: <RequireAuth />, children: [{ path: '/tickets', element: <h1>티켓</h1> }] },
        { path: '/login', element: <h1>로그인</h1> },
      ],
      { initialEntries: ['/tickets?page=2'] },
    );
    render(<RouterProvider router={router} />);
    expect(await screen.findByRole('heading', { name: '로그인' })).toBeVisible();
    expect(router.state.location.state).toMatchObject({ from: '/tickets?page=2' });
  });
});
describe('F001 새로고침 세션 복원', () => {
  it('F001-AC05 Refresh가 유효하면 로그인 화면을 거치지 않고 원래 화면을 유지한다', async () => {
    await login(TEST_LOGINS.requester);
    reload();
    const router = renderProtected();
    expect(screen.getByRole('status')).toHaveTextContent('로그인 상태를 확인하고 있어요');
    expect(await screen.findByRole('heading', { name: '채팅' })).toBeVisible();
    expect(router.state.location.pathname).toBe('/inquiries/1/chat');
    expect(useSession.getState().token).not.toBeNull();
  });
  it('F001-AC05 Refresh가 없거나 만료되면 원래 경로를 보존해 로그인으로 보낸다', async () => {
    reload();
    const router = renderProtected();
    expect(await screen.findByRole('heading', { name: '로그인' })).toBeVisible();
    expect(router.state.location.state).toMatchObject({
      from: '/inquiries/1/chat',
      expired: false,
    });
  });
  it('F001-AC05 인증 서버 장애는 로그인 만료로 확정하지 않고 다시 확인할 수 있다', async () => {
    await login(TEST_LOGINS.requester);
    reload();
    const original = systemMockBackend.request.bind(systemMockBackend);
    const request = vi
      .spyOn(systemMockBackend, 'request')
      .mockImplementationOnce(() => {
        throw new MockHttpError(503, {
          code: 'SERVICE_UNAVAILABLE',
          message: '일시 장애',
          requestId: crypto.randomUUID(),
          retryable: true,
          fieldErrors: [],
        });
      })
      .mockImplementation(original);
    renderProtected();
    expect(await screen.findByRole('alert')).toHaveTextContent('로그인 상태를 확인하지 못했어요');
    await userEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(await screen.findByRole('heading', { name: '채팅' })).toBeVisible();
    expect(request.mock.calls.filter(([path]) => path === '/api/v2/auth/refresh')).toHaveLength(2);
  });
  it('F001-AC05 장애 안내에서 로그인 화면으로 이동할 수 있다', async () => {
    reload();
    vi.spyOn(systemMockBackend, 'request').mockImplementation(() => {
      throw new MockHttpError(503, {
        code: 'SERVICE_UNAVAILABLE',
        message: '일시 장애',
        requestId: crypto.randomUUID(),
        retryable: true,
        fieldErrors: [],
      });
    });
    renderProtected();
    await userEvent.click(await screen.findByRole('button', { name: '로그인 화면으로' }));
    expect(await screen.findByRole('heading', { name: '로그인' })).toBeVisible();
  });
});
