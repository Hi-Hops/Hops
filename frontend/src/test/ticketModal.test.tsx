import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { login, useSession } from '@/features/auth';
import { CasesPage } from '@/pages/cases/CasesPage';
import { TicketsPage } from '@/pages/tickets/TicketsPage';
import { systemGet } from '@/shared/api/systemCommands';
import { systemMockBackend } from '@/shared/api/systemMockBackend';
import { readCases, readTickets } from '@/shared/api/systemWire';
import { realtime } from '@/shared/realtime';
import { TEST_LOGINS } from './mockCredentials';

beforeEach(() => {
  useSession.getState().logout();
  systemMockBackend.reset();
  realtime.disconnect();
});

afterEach(() => {
  realtime.disconnect();
  vi.restoreAllMocks();
  systemMockBackend.reset();
});

async function firstTicket(withSpecialTitle = false) {
  await login(TEST_LOGINS.network);
  const page = await systemGet('/tickets?scope=ASSIGNED_SYSTEMS', readTickets);
  const ticket = page.items.find(
    (item) => item.state === 'WAITING' && (!withSpecialTitle || item.title.includes('[예시]')),
  );
  if (!ticket) throw new Error('담당 티켓 필요');
  return ticket;
}

function renderTickets(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter(
    [
      { path: '/before', element: <h1>이전 화면</h1> },
      { path: '/cases/:caseId?', element: <CasesPage /> },
      { path: '/tickets/:ticketId?', element: <TicketsPage /> },
      { path: '/admin/tickets/:ticketId?', element: <TicketsPage /> },
    ],
    { initialEntries: ['/before', path] },
  );
  render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return router;
}

describe('F009 티켓 상세 모달', () => {
  it('F009-DETAIL-10 탭 이력을 쌓지 않고 진입 전 목록으로 복귀하며 특수문자 제목과 입력을 보존한다', async () => {
    vi.stubEnv('VITE_MOCK_EXAMPLES', 'true');
    systemMockBackend.reset();
    const ticket = await firstTicket(true);
    expect(ticket.title).toContain('[예시]');
    const filters = `view=list&systemId=${ticket.responsibleSystem.id}&state=${ticket.state}&page=0&size=20`;
    const router = renderTickets(`/tickets?${filters}`);
    const user = userEvent.setup();
    const titleMatches = (name: string) => name.includes(ticket.title);
    await user.click(await screen.findByRole('link', { name: titleMatches }));
    const dialog = await screen.findByRole('dialog');
    await user.click(await within(dialog).findByRole('link', { name: '문의 처리 기록' }));
    expect(screen.getByRole('dialog')).toBe(dialog);
    expect(router.state.location.pathname).toBe(`/tickets/${ticket.id}`);
    await user.type(within(dialog).getByRole('textbox', { name: '처리 내용' }), '작성 중인 조치');
    await user.click(within(dialog).getByRole('link', { name: '해결 사례' }));
    await user.click(within(dialog).getByRole('link', { name: '요청·관련 대화' }));
    expect(await within(dialog).findByRole('heading', { name: '표준 요청' })).toBeVisible();
    await user.click(within(dialog).getByRole('link', { name: '문의 처리 기록' }));
    expect(within(dialog).getByRole('textbox', { name: '처리 내용' })).toHaveValue(
      '작성 중인 조치',
    );
    await act(() => router.navigate(-1));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/tickets');
    expect(new URLSearchParams(router.state.location.search)).toEqual(new URLSearchParams(filters));
    await act(() => router.navigate(1));
    const reopened = await screen.findByRole('dialog');
    expect(await within(reopened).findByRole('textbox', { name: '처리 내용' })).toHaveValue(
      '작성 중인 조치',
    );
    await user.click(within(reopened).getByRole('button', { name: '상세 닫기' }));
    await waitFor(() => expect(screen.getByRole('link', { name: titleMatches })).toHaveFocus());
    expect(new URLSearchParams(router.state.location.search)).toEqual(new URLSearchParams(filters));
    await act(() => router.navigate(-1));
    expect(router.state.location.pathname).toBe('/before');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it.each(['닫기', 'Escape'])(
    'F009-DETAIL-10 사례에서 원 티켓의 탭을 전환한 후 %s로 원 사례와 조회 조건에 복귀한다',
    async (close) => {
      vi.stubEnv('VITE_MOCK_EXAMPLES', 'true');
      systemMockBackend.reset();
      await login(TEST_LOGINS.network);
      const cases = await systemGet('/cases', readCases);
      const selected = cases.items[0];
      if (!selected) throw new Error('가상 사례 필요');
      const path = `/cases/${selected.id}?state=DRAFT&page=0&size=10`;
      const router = renderTickets(path);
      const user = userEvent.setup();
      await user.click(await screen.findByRole('link', { name: '원 티켓 보기' }));
      const dialog = await screen.findByRole('dialog');
      await user.click(await within(dialog).findByRole('link', { name: '해결 사례' }));
      if (close === 'Escape') await user.keyboard('{Escape}');
      else await user.click(within(dialog).getByRole('button', { name: '상세 닫기' }));
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      expect(router.state.location.pathname + router.state.location.search).toBe(path);
      await waitFor(() => expect(screen.getByRole('link', { name: '원 티켓 보기' })).toHaveFocus());
      await act(() => router.navigate(-1));
      expect(router.state.location.pathname).toBe('/before');
    },
  );

  it.each(['닫기', 'Escape'])(
    'F009-DETAIL-10 처리 탭 직접 진입 후 %s로 목록 조건과 포커스를 복원한다',
    async (close) => {
      const ticket = await firstTicket();
      const filters = `view=board&systemId=${ticket.responsibleSystem.id}&state=${ticket.state}&page=0&size=10`;
      const router = renderTickets(`/tickets/${ticket.id}?${filters}&tab=work`);
      const user = userEvent.setup();
      const dialog = await screen.findByRole('dialog');
      expect(await within(dialog).findByRole('textbox', { name: '처리 내용' })).toBeVisible();
      // 모달 뒤의 목록은 닫은 뒤 포커스를 돌려줄 대상으로 유지한다.
      expect(
        screen.getByRole('heading', { name: '담당 시스템 업무', hidden: true }),
      ).toBeInTheDocument();
      if (close === 'Escape') await user.keyboard('{Escape}');
      else await user.click(within(dialog).getByRole('button', { name: '상세 닫기' }));
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      expect(router.state.location.pathname).toBe('/tickets');
      expect(new URLSearchParams(router.state.location.search)).toEqual(
        new URLSearchParams(filters),
      );
      await waitFor(() => expect(document.getElementById(`ticket-${ticket.id}`)).toHaveFocus());
    },
  );

  it('F009-DETAIL-10 모달 안에서 처리 기록을 저장하고 이력을 확인한다', async () => {
    const ticket = await firstTicket();
    renderTickets(`/tickets/${ticket.id}?tab=work`);
    const user = userEvent.setup();
    const dialog = await screen.findByRole('dialog');
    await user.type(
      await within(dialog).findByRole('textbox', { name: '처리 내용' }),
      '접근 설정을 점검했어요.',
    );
    await user.click(within(dialog).getByRole('button', { name: '문의 처리 기록 저장' }));
    expect(
      await within(dialog).findByText('접근 설정을 점검했어요.', { selector: 'p' }),
    ).toBeVisible();
    await waitFor(() =>
      expect(within(dialog).getByRole('textbox', { name: '처리 내용' })).toHaveValue(''),
    );
    expect(screen.getByRole('dialog')).toBe(dialog);
  });

  it('F007-CONTRACT-01 관리자 직접 진입에서는 처리 입력 대신 제한 상세 모달을 표시한다', async () => {
    await login(TEST_LOGINS.admin);
    const page = await systemGet('/tickets?scope=ADMIN', readTickets);
    const ticket = page.items[0];
    if (!ticket) throw new Error('관리 티켓 필요');
    renderTickets(`/admin/tickets/${ticket.id}?tab=work`);
    const dialog = await screen.findByRole('dialog');
    expect(await within(dialog).findByRole('heading', { name: '업무 메타데이터' })).toBeVisible();
    expect(within(dialog).queryByRole('textbox', { name: '처리 내용' })).not.toBeInTheDocument();
    expect(
      within(dialog).queryByRole('navigation', { name: '티켓 상세 탭' }),
    ).not.toBeInTheDocument();
  });
});
