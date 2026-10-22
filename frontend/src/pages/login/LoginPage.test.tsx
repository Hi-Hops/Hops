import { screen } from '@testing-library/react';
import { beforeEach, expect, it } from 'vitest';
import { Route, Routes } from 'react-router';
import { useSession } from '@/features/auth';
import { fixture } from '@/shared/api/mockData';
import { renderWithProviders } from '@/test/renderWithProviders';
import { LoginPage } from './LoginPage';
beforeEach(() => {
  useSession.getState().logout();
});
it.each([
  ['login-0', '문의 시작'],
  ['login-1', '부서 티켓'],
  ['login-3', '관리자 티켓'],
])(
  'F001-AC01 로그인 상태에서 로그인 페이지에 재진입하면 %s의 첫 화면으로 이동한다',
  async (id, title) => {
    useSession.getState().activate(fixture(id, 'LoginResponse'));
    renderWithProviders(
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/" element={<h1>문의 시작</h1>} />
        <Route path="/tickets" element={<h1>부서 티켓</h1>} />
        <Route path="/admin/tickets" element={<h1>관리자 티켓</h1>} />
      </Routes>,
      '/login',
    );
    expect(await screen.findByRole('heading', { name: title })).toBeVisible();
  },
);
