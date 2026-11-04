import { screen } from '@testing-library/react';
import { beforeEach, expect, it } from 'vitest';
import { Route, Routes } from 'react-router';
import { login, useSession } from '@/features/auth';
import { systemMockBackend } from '@/shared/api/systemMockBackend';
import { TEST_LOGINS } from '@/test/mockCredentials';
import { sessionFor } from '@/test/systemFixtures';
import { renderWithProviders } from '@/test/renderWithProviders';
import { LoginPage } from './LoginPage';
beforeEach(() => {
  systemMockBackend.reset();
  useSession.getState().logout();
});
function renderLogin() {
  renderWithProviders(
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/" element={<h1>문의 시작</h1>} />
    </Routes>,
    '/login',
  );
}
it.each([
  ['requester', '문의 시작'],
  ['staff', '담당 시스템 업무'],
  ['admin', '관리자 티켓'],
] as const)(
  'F001-AC01 로그인 상태에서 로그인 페이지에 재진입하면 %s의 첫 화면으로 이동한다',
  async (id, title) => {
    useSession.getState().activate(sessionFor(id));
    renderWithProviders(
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/" element={<h1>문의 시작</h1>} />
        <Route path="/tickets" element={<h1>담당 시스템 업무</h1>} />
        <Route path="/admin/tickets" element={<h1>관리자 티켓</h1>} />
      </Routes>,
      '/login',
    );
    expect(await screen.findByRole('heading', { name: title })).toBeVisible();
  },
);
it('F001-AC05 로그인 화면에서 새로고침해도 Refresh가 유효하면 첫 화면으로 이동한다', async () => {
  await login(TEST_LOGINS.requester);
  useSession.setState({ user: null, token: null, expiresAt: null, restoreStatus: 'pending' });
  renderLogin();
  expect(screen.getByRole('status')).toHaveTextContent('로그인 상태를 확인하고 있어요');
  expect(await screen.findByRole('heading', { name: '문의 시작' })).toBeVisible();
});
it('F001-AC05 Refresh가 없으면 로그인 화면에서 바로 로그인 폼을 보여준다', async () => {
  useSession.setState({ restoreStatus: 'pending' });
  renderLogin();
  expect(await screen.findByRole('heading', { name: 'Hops에 로그인' })).toBeVisible();
});
