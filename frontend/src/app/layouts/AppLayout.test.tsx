import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { Route, Routes } from 'react-router';
import { login, useSession } from '@/features/auth';
import { HomePage } from '@/pages/home/HomePage';
import { InquiryChatPage } from '@/pages/inquiry-chat/InquiryChatPage';
import { systemMockBackend } from '@/shared/api/systemMockBackend';
import { systemGet } from '@/shared/api/systemCommands';
import { readInquiries } from '@/shared/api/systemWire';
import { realtime } from '@/shared/realtime';
import { TEST_LOGINS } from '@/test/mockCredentials';
import { renderWithProviders } from '@/test/renderWithProviders';
import { AppLayout } from './AppLayout';

beforeEach(() => {
  systemMockBackend.reset();
  useSession.getState().logout();
});
afterEach(() => {
  realtime.disconnect();
  systemMockBackend.reset();
});
function renderLayout(path = '/') {
  return renderWithProviders(
    <Routes>
      <Route element={<AppLayout />}>
        <Route path="/" element={<HomePage />} />
        <Route path="/inquiries/:inquiryId/chat" element={<InquiryChatPage />} />
        <Route path="/tickets" element={<h1>담당 업무</h1>} />
        <Route path="/admin/tickets" element={<h1>관리자 업무</h1>} />
      </Route>
    </Routes>,
    path,
  );
}

describe('F003/F006 사이드바 이동', () => {
  it.each([
    ['requester', false, false],
    ['network', true, false],
    ['admin', false, true],
  ] as const)(
    'F001-CONTRACT-02 %s의 시스템 역할에 맞는 메뉴와 개인 세션을 제공한다',
    async (role, staff, admin) => {
      await login(TEST_LOGINS[role]);
      renderLayout();
      const menu = within(screen.getByRole('navigation', { name: '주 메뉴' }));
      expect(menu.getByRole('link', { name: '+ 새 문의' })).toHaveAttribute('href', '/');
      expect(menu.getByRole('link', { name: '내 문의' })).toHaveAttribute('href', '/inquiries');
      expect(Boolean(menu.queryByRole('link', { name: '담당 시스템 업무' }))).toBe(staff);
      expect(Boolean(menu.queryByRole('link', { name: '업무 관리' }))).toBe(admin);
      expect(screen.getByRole('region', { name: '채팅 세션' })).toBeVisible();
      await screen.findByLabelText('문의 내용');
    },
  );
  it('F003-CONTRACT-04/F006-AC10 화면 이동 시 초안을 유지하고 첫 저장 뒤 새 세션을 추가한다', async () => {
    await login(TEST_LOGINS.requester);
    const initial = await systemGet('/inquiries?page=0&size=20', readInquiries);
    const seed = initial.items[0];
    if (!seed) throw new Error('기존 문의 필요');
    const user = userEvent.setup();
    renderLayout(`/inquiries/${seed.id}/chat`);
    await screen.findByLabelText('메시지 입력');
    if (screen.queryByRole('dialog')) await user.keyboard('{Escape}');
    await waitFor(() => {
      expect(screen.getByLabelText('메시지 입력')).toBeEnabled();
    });
    await user.type(screen.getByLabelText('메시지 입력'), '이전 문의의 미전송 내용');
    const menu = within(screen.getByRole('navigation', { name: '주 메뉴' }));
    await user.click(menu.getByRole('link', { name: '+ 새 문의' }));
    expect(await screen.findByLabelText('문의 내용')).toHaveFocus();
    expect((await systemGet('/inquiries?page=0&size=20', readInquiries)).totalElements).toBe(
      initial.totalElements,
    );
    await user.type(screen.getByLabelText('문의 내용'), '전자결재 새 문의');
    await user.click(menu.getByRole('link', { name: '+ 새 문의' }));
    expect(screen.getByLabelText('문의 내용')).toHaveValue('전자결재 새 문의');
    const history = within(screen.getByRole('region', { name: '채팅 세션' }));
    await user.click(await history.findByRole('link', { name: new RegExp(`^${seed.title}`, 'u') }));
    expect(await screen.findByLabelText('메시지 입력')).toHaveValue('이전 문의의 미전송 내용');
    if (screen.queryByRole('dialog')) await user.keyboard('{Escape}');
    await user.click(menu.getByRole('link', { name: '+ 새 문의' }));
    expect(await screen.findByLabelText('문의 내용')).toHaveValue('전자결재 새 문의');
    await user.click(screen.getByRole('button', { name: '질문하기 ↑' }));
    await screen.findByLabelText('메시지 입력');
    await waitFor(() => {
      expect(history.getAllByRole('link')).toHaveLength(initial.totalElements + 1);
    });
    expect((await systemGet('/inquiries?page=0&size=20', readInquiries)).totalElements).toBe(
      initial.totalElements + 1,
    );
  });
});
