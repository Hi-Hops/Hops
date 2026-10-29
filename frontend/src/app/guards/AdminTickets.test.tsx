import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Route, Routes, useLocation } from 'react-router';
import { RequireRole } from '@/app/guards/RequireRole';
import { login, useSession } from '@/features/auth';
import { RealtimeBoundary } from '@/features/realtime';
import { mockBackend } from '@/shared/api/mockBackend';
import { fixture } from '@/shared/api/mockData';
import { realtime } from '@/shared/realtime/mockTransport';
import { TEST_LOGINS } from '@/test/mockCredentials';
import { renderWithProviders } from '@/test/renderWithProviders';
import * as ticketApi from '@/features/ticket/api/ticketApi';
import { TicketBoard } from '@/features/ticket';

beforeEach(async () => {
  mockBackend.reset();
  useSession.getState().logout();
  await login(TEST_LOGINS.admin);
});
afterEach(() => {
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
                <TicketBoard />
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
describe('F007 관리자 티켓 읽기 전용 조회', () => {
  it('F007-AC01 일반 처리 부서원은 관리자 화면·조회·SSE를 시작하지 않는다', async () => {
    await login(TEST_LOGINS.network);
    const request = vi.spyOn(ticketApi, 'getTickets');
    const subscribe = vi.spyOn(realtime, 'subscribe');
    view();
    expect(await screen.findByRole('heading', { name: '권한 없음' })).toBeVisible();
    expect(request).not.toHaveBeenCalled();
    expect(subscribe).not.toHaveBeenCalled();
  });
  it('F007-AC02 F007-AC05 전체 목록에서 다른 부서 상세를 읽고 관리자 경로로 복귀한다', async () => {
    view('/admin/tickets?processingStatus=WAITING&size=20');
    const table = await screen.findByRole('table');
    expect(within(table).getByText('TK-DEMO-001')).toBeVisible();
    const link = within(table).getByRole('link', { name: /TK-DEMO-002/u });
    await userEvent.click(link);
    const dialog = await screen.findByRole('dialog');
    expect(await within(dialog).findByRole('heading', { name: 'AI 진단 요약' })).toBeVisible();
    expect(screen.getByLabelText('현재 주소')).toHaveTextContent('/admin/tickets/');
    expect(
      within(dialog).queryByRole('button', { name: /수동 배정|재이관|처리 완료/u }),
    ).not.toBeInTheDocument();
    await userEvent.keyboard('{Escape}');
    await waitFor(() => {
      expect(link).toHaveFocus();
    });
    expect(screen.getByLabelText('현재 주소')).toHaveTextContent(
      '/admin/tickets?processingStatus=WAITING&size=20',
    );
  });
  it('F007-AC03 배정 예외·복구 대기·CS 검토를 처리 상태와 별개로 표시한다', async () => {
    const seed = fixture('ticket-network', 'TicketCard');
    const items = ['EXCEPTION', 'RECOVERY_PENDING', 'CS_REVIEW'].map((routingStatus) => ({
      ...seed,
      id: crypto.randomUUID(),
      routingStatus,
    }));
    vi.spyOn(ticketApi, 'getTickets').mockResolvedValue({
      items,
      page: 0,
      size: 20,
      totalElements: 3,
    });
    view();
    const table = await screen.findByRole('table');
    for (const label of ['배정 예외', '복구 대기', 'CS 검토'])
      expect(within(table).getByText(label)).toBeVisible();
    expect(within(table).getAllByText('처리 대기')).toHaveLength(3);
  });
  it('F007-AC07 수동 새로고침으로 전체 REST 결과를 다시 읽는다', async () => {
    const request = vi.spyOn(ticketApi, 'getTickets');
    view();
    await screen.findByRole('table');
    request.mockClear();
    await userEvent.click(screen.getByRole('button', { name: '새로고침' }));
    await waitFor(() => {
      expect(request).toHaveBeenCalled();
    });
  });
  it('F007-AC07 부서 SSE 없이도 주기적 전체 조회로 다른 부서의 새 티켓을 반영한다', async () => {
    const network = fixture('ticket-network', 'TicketCard');
    const backend = fixture('ticket-backend', 'TicketCard');
    const request = vi
      .spyOn(ticketApi, 'getTickets')
      .mockResolvedValue({ items: [network], page: 0, size: 20, totalElements: 1 });
    view();
    await screen.findByRole('table');
    expect(screen.queryByText(backend.ticketNumber)).not.toBeInTheDocument();
    request.mockResolvedValue({ items: [network, backend], page: 0, size: 20, totalElements: 2 });
    await waitFor(
      () => {
        expect(screen.getByText(backend.ticketNumber)).toBeVisible();
      },
      { timeout: 6500 },
    );
    expect(request.mock.calls.every(([query]) => !query.includes('departmentId'))).toBe(true);
  }, 10000);
  it('F007-AC08 조회 실패를 빈 결과로 표시하지 않고 재시도한다', async () => {
    const request = vi.spyOn(ticketApi, 'getTickets').mockRejectedValue(new Error('조회 실패'));
    view();
    expect(await screen.findByRole('alert')).toHaveTextContent('티켓 목록을 불러오지 못했어요');
    expect(screen.queryByText('배정된 티켓이 없어요')).not.toBeInTheDocument();
    request.mockResolvedValue({ items: [], page: 0, size: 20, totalElements: 0 });
    await userEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(await screen.findByText('배정된 티켓이 없어요')).toBeVisible();
  });
});
