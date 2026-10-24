import { TEST_LOGINS } from '@/test/mockCredentials';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { Route, Routes, useLocation } from 'react-router';
import { login, useSession } from '@/features/auth';
import { RealtimeBoundary } from '@/features/realtime';
import { mockBackend } from '@/shared/api/mockBackend';
import { mockWorkspace } from '@/shared/api/mockWorkspace';
import { fixture } from '@/shared/api/mockData';
import { realtime } from '@/shared/realtime/mockTransport';
import { renderWithProviders } from '@/test/renderWithProviders';
import { TicketBoard } from './TicketBoard';
beforeEach(async () => {
  mockBackend.reset();
  useSession.getState().logout();
  realtime.disconnect();
  await login(TEST_LOGINS.network);
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
const network = () => fixture('ticket-network', 'TicketCard');
describe('F009 부서 티켓 조회', () => {
  it('F009-AC01 서버가 허용한 현재 부서의 티켓만 표시한다', async () => {
    view();
    expect(await screen.findByRole('link', { name: /TK-DEMO-001/u })).toBeVisible();
    expect(screen.queryByText('TK-DEMO-002')).not.toBeInTheDocument();
    expect(screen.getByRole('table')).toHaveTextContent('네트워크');
  });
  it('F009-AC02 처리 상태와 배정 상태를 따로 표시한다', async () => {
    view();
    const table = await screen.findByRole('table');
    expect(within(table).getByText('처리 대기')).toBeVisible();
    expect(within(table).getByText('부서 배정됨')).toBeVisible();
  });
  it('F009-AC03 필터·페이지를 URL에 보존하고 Esc 닫기 후 링크로 포커스를 돌린다', async () => {
    view('/tickets?processingStatus=WAITING&page=0&size=1');
    const link = await screen.findByRole('link', { name: /TK-DEMO-001/u });
    const user = userEvent.setup();
    await user.click(link);
    expect(await screen.findByRole('dialog')).toBeVisible();
    await screen.findByRole('heading', { name: '문의 요약' });
    await user.keyboard('{Escape}');
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });
    expect(screen.getByLabelText('현재 주소')).toHaveTextContent(
      '/tickets?processingStatus=WAITING&page=0&size=1',
    );
    expect(screen.getByLabelText('처리 상태')).toHaveValue('WAITING');
    await waitFor(() => {
      expect(screen.getByRole('link', { name: /TK-DEMO-001/u })).toHaveFocus();
    });
  });
  it('F009-AC03 상태 필터 변경으로 페이지를 초기화하고 권한 범위를 넓히지 않는다', async () => {
    view('/tickets?page=1&size=1');
    expect(await screen.findByText('배정된 티켓이 없어요')).toBeVisible();
    await userEvent.selectOptions(screen.getByLabelText('처리 상태'), 'WAITING');
    expect(await screen.findByRole('link', { name: /TK-DEMO-001/u })).toBeVisible();
    expect(screen.getByLabelText('현재 주소')).toHaveTextContent('size=1&processingStatus=WAITING');
    expect(screen.queryByText('TK-DEMO-002')).not.toBeInTheDocument();
  });
  it('F009-AC04 빈 결과는 조회 실패와 다르게 표시한다', async () => {
    view('/tickets?processingStatus=RESOLVED');
    expect(await screen.findByText('배정된 티켓이 없어요')).toBeVisible();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
  it('F009-AC04 조회 실패 후 다시 시도하면 목록을 복구한다', async () => {
    mockBackend.failNext();
    view();
    expect(await screen.findByRole('alert')).toHaveTextContent('티켓 목록을 불러오지 못했어요');
    expect(screen.queryByText('배정된 티켓이 없어요')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(await screen.findByRole('table')).toBeVisible();
  });
  it('F009-AC05 상세는 요약·진단·현재 부서·배정 이력만 읽을 수 있다', async () => {
    view(`/tickets/${network().id}`);
    const dialog = await screen.findByRole('dialog');
    expect(await within(dialog).findByText('가상 VPN 연결 시 오류가 발생합니다.')).toBeVisible();
    expect(within(dialog).getByRole('heading', { name: 'AI 진단 요약' })).toBeVisible();
    expect(within(dialog).getByRole('heading', { name: '배정 이력' })).toBeVisible();
    expect(
      within(dialog).queryByRole('button', { name: /가져오기|처리 완료|재이관|정정/u }),
    ).not.toBeInTheDocument();
  });
  it('F009-AC06 다른 부서 상세는 내용을 노출하지 않고 목록 복귀를 안내한다', async () => {
    const other = fixture('ticket-backend', 'TicketCard');
    view(`/tickets/${other.id}`);
    const dialog = await screen.findByRole('dialog');
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      '이 티켓을 볼 권한이 없어요',
    );
    expect(within(dialog).queryByText(other.title)).not.toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole('button', { name: '부서 티켓 목록으로' }));
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });
  });
  it('F009-AC06 존재하지 않는 티켓은 권한 오류와 구분한다', async () => {
    view('/tickets/00000000-0000-4000-8000-999999999999');
    const dialog = await screen.findByRole('dialog');
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('티켓을 찾을 수 없어요');
  });
  it('F009-AC01 업무 관리자는 허용된 전체 목록을 조회한다', async () => {
    useSession.getState().logout();
    await login(TEST_LOGINS.admin);
    view();
    expect(await screen.findByText('TK-DEMO-002')).toBeVisible();
    expect(screen.getByText('TK-DEMO-001')).toBeVisible();
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('전체 티켓 현황');
  });
  it('F009-AC03 다음·이전 페이지 이동을 주소에 반영한다', async () => {
    useSession.getState().logout();
    await login(TEST_LOGINS.admin);
    view('/tickets?size=1');
    await screen.findByRole('table');
    await userEvent.click(screen.getByRole('button', { name: '다음' }));
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '다음' })).toBeDisabled();
    });
    expect(screen.getByLabelText('현재 주소')).toHaveTextContent('page=1');
    await userEvent.click(screen.getByRole('button', { name: '이전' }));
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '이전' })).toBeDisabled();
    });
  });
  it('F009-AC06 재이관 이후 상세를 재조회하면 기존 내용이 가려진다', async () => {
    const { client } = view(`/tickets/${network().id}`);
    const dialog = await screen.findByRole('dialog');
    await within(dialog).findByText('가상 VPN 연결 시 오류가 발생합니다.');
    mockWorkspace.reassignForTest(network().id, fixture('department-4', 'Department'));
    await client.invalidateQueries({ queryKey: ['tickets'] });
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      '이 티켓을 볼 권한이 없어요',
    );
    expect(
      within(dialog).queryByText('가상 VPN 연결 시 오류가 발생합니다.'),
    ).not.toBeInTheDocument();
  });
  it('F009-AC06 일반 문의자는 직접 URL로 부서 티켓을 조회할 수 없다', async () => {
    useSession.getState().logout();
    await login(TEST_LOGINS.requester);
    view();
    expect(screen.getByRole('alert')).toHaveTextContent('조회 권한이 없어요');
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });
  it('F009-AC01 변경 이벤트와 재접속 뒤 REST 결과를 갱신한다', async () => {
    view();
    await screen.findByRole('table');
    const owner = fixture('user-0', 'CurrentUser');
    const ack = mockWorkspace.command(owner, 'inquiry.create', {
      requestId: crypto.randomUUID(),
      content: 'VPN 신규 문의',
    });
    if (!ack.ok) throw new Error('문의 생성 실패');
    mockWorkspace.flushPending();
    mockWorkspace.command(owner, 'message.send', {
      requestId: crypto.randomUUID(),
      inquiryId: ack.inquiryId,
      expectedInquiryVersion: 1,
      content: '809 오류',
    });
    mockWorkspace.flushPending();
    await waitFor(() => {
      expect(within(screen.getByRole('table')).getAllByRole('link')).toHaveLength(2);
    });
    realtime.interrupt();
    await userEvent.click(await screen.findByRole('button', { name: '다시 연결' }));
    expect(await screen.findByRole('table')).toBeVisible();
  });
});
