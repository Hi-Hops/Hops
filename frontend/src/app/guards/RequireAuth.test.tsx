import { render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { beforeEach, describe, expect, it } from 'vitest';
import { fixture } from '@/shared/api/mockData';
import { useSession } from '@/features/auth';
import { RequireAuth } from './RequireAuth';
import { RequireRole } from './RequireRole';

beforeEach(() => {
  useSession.getState().logout();
});
describe('F001 경로 권한', () => {
  it('F001-AC04 일반 문의자에게 관리자 내용을 표시하지 않는다', async () => {
    useSession.getState().activate(fixture('login-0', 'LoginResponse'));
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
