import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Route, Routes, useLocation } from 'react-router';
import { RequireRole } from '@/app/guards/RequireRole';
import { login, useSession } from '@/features/auth';
import { RealtimeBoundary } from '@/features/realtime';
import { systemMockBackend } from '@/shared/api/systemMockBackend';
import { realtime } from '@/shared/realtime';
import { TEST_LOGINS } from '@/test/mockCredentials';
import { renderWithProviders } from '@/test/renderWithProviders';
import * as ticketApi from '@/features/ticket/api/systemTicketApi';
import { TicketsPage } from '@/pages/tickets/TicketsPage';

beforeEach(async () => {
  systemMockBackend.reset();
  useSession.getState().logout();
  await login(TEST_LOGINS.admin);
});
afterEach(() => {
  realtime.disconnect();
  systemMockBackend.reset();
  vi.restoreAllMocks();
});
function Location() {
  const location = useLocation();
  return (
    <output aria-label="현재 주소">
      {location.pathname}
      {location.search}
    </output>
  );
}
function view(path = '/admin/tickets') {
  return renderWithProviders(
    <>
      <Location />
      <Routes>
        <Route element={<RequireRole role="BIZ_ADMIN" />}>
          <Route
            path="/admin/tickets/:ticketId?"
            element={
              <RealtimeBoundary>
                <TicketsPage />
              </RealtimeBoundary>
            }
          />
        </Route>
        <Route path="/403" element={<h1>권한 없음</h1>} />
      </Routes>
    </>,
    path,
  );
}

describe('F007 관리자 업무 조회', () => {
  it('F007-CONTRACT-01 관리자가 아닌 담당자는 관리 조회·SSE를 시작하지 않는다', async () => {
    await login(TEST_LOGINS.network);
    const request = vi.spyOn(ticketApi, 'getSystemTickets');
    const subscribe = vi.spyOn(realtime, 'subscribe');
    view();
    expect(await screen.findByRole('heading', { name: '권한 없음' })).toBeVisible();
    expect(request).not.toHaveBeenCalled();
    expect(subscribe).not.toHaveBeenCalled();
  });
  it('F007-CONTRACT-01 제한된 관리 상세를 모달로 열고 필터를 유지해 닫는다', async () => {
    const tickets = await ticketApi.getSystemTickets('scope=ADMIN');
    const ticket = tickets.items[0];
    if (!ticket) throw new Error('관리 티켓 필요');
    const internal = vi.spyOn(ticketApi, 'getSystemTicket');
    view('/admin/tickets?state=WAITING&size=20');
    const table = await screen.findByRole('table');
    await userEvent.click(within(table).getByRole('link', { name: new RegExp(ticket.title, 'u') }));
    expect(
      await screen.findByRole('dialog', { name: new RegExp(ticket.title, 'u') }),
    ).toBeVisible();
    expect(
      screen.queryByRole('heading', { name: '확인 사실 · 추정 · 미확인' }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole('heading', { name: '관련 대화 · 보완 정보' }),
    ).not.toBeInTheDocument();
    expect(internal).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole('button', { name: '상세 닫기' }));
    expect(await screen.findByRole('table')).toBeVisible();
    expect(screen.getByLabelText('현재 주소')).toHaveTextContent(
      '/admin/tickets?state=WAITING&size=20',
    );
  });
  it('F007-AC07 새로고침과 ADMIN 범위·시스템 필터를 서버에 전달한다', async () => {
    const request = vi.spyOn(ticketApi, 'getSystemTickets');
    view('/admin/tickets?view=list&state=WAITING&size=20');
    await screen.findByRole('table');
    expect(request.mock.calls[0]?.[0]).toBe('scope=ADMIN&state=WAITING&size=20');
    request.mockClear();
    await userEvent.click(screen.getByRole('button', { name: '새로고침' }));
    await waitFor(() => {
      expect(request).toHaveBeenCalled();
    });
  });
  it('F007-AC07 보조 REST 폴링으로 메타데이터 알림 유실을 보완한다', async () => {
    const full = await ticketApi.getSystemTickets('scope=ADMIN');
    const request = vi
      .spyOn(ticketApi, 'getSystemTickets')
      .mockResolvedValue({ ...full, items: full.items.slice(0, 1), totalElements: 1 });
    view();
    await screen.findByRole('table');
    expect(screen.getAllByRole('row')).toHaveLength(2);
    request.mockResolvedValue(full);
    await waitFor(
      () => {
        expect(screen.getAllByRole('row')).toHaveLength(full.items.length + 1);
      },
      { timeout: 6500 },
    );
  }, 10000);
  it('F007-AC08 조회 실패와 빈 결과를 구분하고 재시도한다', async () => {
    const request = vi
      .spyOn(ticketApi, 'getSystemTickets')
      .mockRejectedValue(new Error('조회 실패'));
    view();
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '티켓 또는 시스템 목록을 불러오지 못했어요',
    );
    expect(screen.queryByText('배정된 티켓이 없어요')).not.toBeInTheDocument();
    request.mockResolvedValue({ items: [], page: 0, size: 20, totalElements: 0 });
    await userEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(await screen.findByText('배정된 티켓이 없어요')).toBeVisible();
  });
});
