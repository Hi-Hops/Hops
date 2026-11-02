import { screen, within, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Route, Routes, useLocation } from 'react-router';
import { login, useSession } from '@/features/auth';
import { RealtimeBoundary } from '@/features/realtime';
import { mockBackend } from '@/shared/api/mockBackend';
import { fixture } from '@/shared/api/mockData';
import type { ProcessingStatus } from '@/shared/api/types';
import { TEST_LOGINS } from '@/test/mockCredentials';
import { renderWithProviders } from '@/test/renderWithProviders';
import * as ticketApi from '../api/ticketApi';
import { TicketBoard } from './TicketBoard';
import { TicketKanban } from './TicketKanban';

beforeEach(async () => {
  mockBackend.reset();
  useSession.getState().logout();
  await login(TEST_LOGINS.network);
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
function view(path = '/tickets') {
  return renderWithProviders(
    <>
      <Location />
      <Routes>
        <Route
          path="/tickets/:ticketId?"
          element={
            <RealtimeBoundary>
              <TicketBoard />
            </RealtimeBoundary>
          }
        />
      </Routes>
    </>,
    path,
  );
}
describe('F009 칸반 조회', () => {
  it('F009-AC01 F009-AC12 기본 칸반에서 현재 부서 카드로 상세를 열고 닫으면 포커스를 복귀한다', async () => {
    view();
    const board = await screen.findByRole('region', { name: '티켓 칸반 보드' });
    const link = within(board).getByRole('link', { name: /TK-DEMO-001/u });
    expect(screen.getByRole('radio', { name: '보드' })).toBeChecked();
    expect(within(board).queryByText('TK-DEMO-002')).not.toBeInTheDocument();
    await userEvent.click(link);
    expect(await screen.findByRole('dialog')).toBeVisible();
    await userEvent.keyboard('{Escape}');
    await waitFor(() => {
      expect(link).toHaveFocus();
    });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
  it('F009-AC02 F009-AC11 처리 상태 컬럼과 배정 예외를 구분하고 종료의 두 상태를 숨기지 않는다', () => {
    const seed = fixture('ticket-network', 'TicketCard');
    const states: ProcessingStatus[] = [
      'WAITING',
      'IN_PROGRESS',
      'WAITING_CONFIRM',
      'RESOLVED',
      'CLOSED_UNCONFIRMED',
    ];
    const items = states.map((processingStatus, index) => ({
      ...seed,
      id: crypto.randomUUID(),
      title: `카드 ${String(index)}`,
      processingStatus,
      routingStatus: 'EXCEPTION',
    }));
    renderWithProviders(<TicketKanban items={items} base="/tickets" search="" />);
    expect(
      within(screen.getByRole('region', { name: '처리 대기 컬럼' })).getAllByRole('link'),
    ).toHaveLength(1);
    const closed = screen.getByRole('region', { name: '종료 컬럼' });
    expect(within(closed).getAllByRole('link')).toHaveLength(2);
    expect(within(closed).getByText('해결 완료')).toBeVisible();
    expect(within(closed).getByText('미확인 종료')).toBeVisible();
    expect(screen.getAllByText('배정 예외')).toHaveLength(5);
    expect(screen.queryByRole('button', { name: /가져오기|재이관/u })).not.toBeInTheDocument();
  });
  it('F009-AC11 미등록 처리 상태는 컬럼 밖으로 누락시키지 않는다', () => {
    // 외부 응답이 새 상태를 보내는 상황을 재현하는 데이터 경계다.
    const item = {
      ...fixture('ticket-network', 'TicketCard'),
      processingStatus: 'FUTURE_STATUS' as ProcessingStatus,
    };
    renderWithProviders(<TicketKanban items={[item]} base="/tickets" search="" />);
    expect(
      within(screen.getByRole('region', { name: '상태 확인 필요 컬럼' })).getByRole('link'),
    ).toHaveTextContent('FUTURE_STATUS');
  });
  it('F009-AC03 F009-AC12 보드/목록을 전환해도 필터·페이지를 유지하고 view는 서버에 보내지 않는다', async () => {
    const request = vi.spyOn(ticketApi, 'getTickets');
    view('/tickets?view=board&processingStatus=WAITING&page=0&size=1');
    await screen.findByRole('region', { name: '티켓 칸반 보드' });
    await userEvent.click(screen.getByRole('radio', { name: '목록' }));
    expect(await screen.findByRole('table')).toBeVisible();
    expect(screen.getByLabelText('현재 주소')).toHaveTextContent(
      'view=list&processingStatus=WAITING&page=0&size=1',
    );
    expect(request.mock.calls.length).toBeGreaterThan(0);
    expect(request.mock.calls.every(([query]) => !query.includes('view='))).toBe(true);
    expect(request.mock.calls.every(([query]) => query.includes('processingStatus=WAITING'))).toBe(
      true,
    );
    await userEvent.click(screen.getByRole('radio', { name: '보드' }));
    expect(await screen.findByRole('region', { name: '티켓 칸반 보드' })).toBeVisible();
  });
  it('F009-AC11 전체 건수를 현재 페이지 컬럼 건수로 잘못 표시하지 않는다', async () => {
    vi.spyOn(ticketApi, 'getTickets').mockResolvedValue({
      items: [fixture('ticket-network', 'TicketCard')],
      page: 0,
      size: 20,
      totalElements: 400,
    });
    view();
    await screen.findByRole('region', { name: '티켓 칸반 보드' });
    expect(screen.getByLabelText('현재 페이지 1건')).toBeVisible();
    expect(screen.getByText(/전체 400건/u)).toBeVisible();
    expect(screen.getByText(/컬럼 숫자는 현재 페이지/u)).toBeVisible();
  });
});
