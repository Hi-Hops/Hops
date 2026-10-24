import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { Route, Routes } from 'react-router';
import { login, useSession } from '@/features/auth';
import { HomePage } from '@/pages/home/HomePage';
import { InquiryChatPage } from '@/pages/inquiry-chat/InquiryChatPage';
import { mockBackend } from '@/shared/api/mockBackend';
import { mockWorkspace } from '@/shared/api/mockWorkspace';
import { fixture } from '@/shared/api/mockData';
import { TEST_LOGINS } from '@/test/mockCredentials';
import { renderWithProviders } from '@/test/renderWithProviders';
import { AppLayout } from './AppLayout';

beforeEach(() => {
  mockBackend.reset();
  useSession.getState().logout();
});
function renderLayout(path = '/') {
  return renderWithProviders(
    <Routes>
      <Route element={<AppLayout />}>
        <Route path="/" element={<HomePage />} />
        <Route path="/inquiries/:inquiryId/chat" element={<InquiryChatPage />} />
        <Route path="/tickets" element={<h1>부서 화면</h1>} />
        <Route path="/admin/tickets" element={<h1>관리자 화면</h1>} />
      </Route>
    </Routes>,
    path,
  );
}
describe('F003/F006 사이드바 이동', () => {
  it.each([
    ['requester', false, false],
    ['network', true, false],
    ['admin', true, true],
  ] as const)(
    'F003-AC15 %s 권한에 맞는 상단 메뉴와 하단 개인 세션을 제공한다',
    async (role, department, admin) => {
      await login(TEST_LOGINS[role]);
      renderLayout();
      const menu = within(screen.getByRole('navigation', { name: '주 메뉴' }));
      expect(menu.getByRole('link', { name: '+ 새 문의' })).toHaveAttribute('href', '/');
      expect(Boolean(menu.queryByRole('link', { name: '부서 티켓 현황' }))).toBe(department);
      expect(Boolean(menu.queryByRole('link', { name: '관리자 페이지' }))).toBe(admin);
      expect(menu.queryByRole('link', { name: '내 문의 내역' })).not.toBeInTheDocument();
      expect(screen.getByRole('region', { name: '채팅 세션' })).toBeVisible();
      await screen.findByLabelText('문의 내용');
    },
  );
  it('F003-AC15/F006-AC10 기존 대화와 새 문의 입력을 보존하고 첫 저장 후 세션을 추가한다', async () => {
    await login(TEST_LOGINS.requester);
    const seed = fixture('inquiry-result', 'InquiryResult');
    const user = userEvent.setup();
    const rendered = renderLayout(`/inquiries/${seed.id}/chat`);
    await screen.findByLabelText('메시지 입력');
    await user.type(screen.getByLabelText('메시지 입력'), '이전 문의의 미전송 내용');
    const actor = useSession.getState().user;
    if (!actor) throw new Error('로그인 필요');
    const before = mockWorkspace.listInquiries(actor, 0, 20).totalElements;
    const menu = within(screen.getByRole('navigation', { name: '주 메뉴' }));
    await user.click(menu.getByRole('link', { name: '+ 새 문의' }));
    expect(await screen.findByLabelText('문의 내용')).toHaveFocus();
    expect(mockWorkspace.listInquiries(actor, 0, 20).totalElements).toBe(before);
    await user.type(screen.getByLabelText('문의 내용'), 'VPN 새 문의');
    await user.click(menu.getByRole('link', { name: '+ 새 문의' }));
    expect(screen.getByLabelText('문의 내용')).toHaveFocus();
    expect(screen.getByLabelText('문의 내용')).toHaveValue('VPN 새 문의');
    const history = within(screen.getByRole('region', { name: '채팅 세션' }));
    await user.click(await history.findByRole('link'));
    expect(await screen.findByLabelText('메시지 입력')).toHaveValue('이전 문의의 미전송 내용');
    await user.click(menu.getByRole('link', { name: '+ 새 문의' }));
    expect(await screen.findByLabelText('문의 내용')).toHaveValue('VPN 새 문의');
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '질문하기 ↑' })).toBeEnabled();
    });
    await user.click(screen.getByRole('button', { name: '질문하기 ↑' }));
    await screen.findByLabelText('메시지 입력');
    await waitFor(() => {
      expect(history.getAllByRole('link')).toHaveLength(before + 1);
    });
    expect(mockWorkspace.listInquiries(actor, 0, 20).totalElements).toBe(before + 1);
    await user.click(history.getByRole('link', { name: /^VPN 접속 오류/u }));
    await screen.findByLabelText('메시지 입력');
    mockWorkspace.failNextCommand();
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '보내기 ↑' })).toBeEnabled();
    });
    await user.click(screen.getByRole('button', { name: '보내기 ↑' }));
    await screen.findByText(/저장하지 못했어요/u);
    await user.click(history.getByRole('link', { name: /^VPN 새 문의/u }));
    await waitFor(() => {
      expect(screen.queryByText(/저장하지 못했어요/u)).not.toBeInTheDocument();
    });
    expect(screen.getByLabelText('메시지 입력')).toHaveValue('');
    act(() => {
      rendered.client.clear();
    });
  });
});
