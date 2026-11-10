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
import { readCases, readTicket, readTickets } from '@/shared/api/systemWire';
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
  it.each(['board', 'list'])(
    'F009-DETAIL-02 담당 업무 %s에서 본인 이름과 중복 시스템 표시를 생략한다',
    async (view) => {
      const ticket = await firstTicket();
      renderTickets(`/tickets?view=${view}`);
      const row = await screen.findByRole('link', { name: new RegExp(ticket.title, 'u') });
      expect(row).not.toHaveTextContent(ticket.assignee.name);
      expect(row).not.toHaveTextContent(`보고 ${ticket.reportedSystem.name}`);
      expect(screen.queryByRole('columnheader', { name: '배정 담당자' })).not.toBeInTheDocument();
    },
  );

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
    await user.click(within(dialog).getByRole('link', { name: '요청 내역' }));
    expect(await within(dialog).findByRole('heading', { name: '문의 요약' })).toBeVisible();
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

  it('F009-DETAIL-05 진행 기록은 처리 내용만 받고 완료 기록은 저장 전에 전달 내용을 보여준다', async () => {
    const ticket = await firstTicket();
    renderTickets(`/tickets/${ticket.id}?tab=work`);
    const user = userEvent.setup();
    const dialog = await screen.findByRole('dialog');
    const view = within(dialog);
    await user.type(await view.findByRole('textbox', { name: '처리 내용' }), '저장 경로를 복구');
    expect(view.queryByRole('textbox', { name: /동작 확인 결과/ })).not.toBeInTheDocument();
    expect(view.queryByRole('textbox', { name: /확인된 원인/ })).not.toBeInTheDocument();

    await user.selectOptions(view.getByRole('combobox', { name: '기록 유형' }), '처리 완료');
    await user.type(view.getByRole('textbox', { name: '동작 확인 결과 (필수)' }), '정상 열람 확인');
    await user.type(view.getByRole('textbox', { name: '확인된 원인 (선택)' }), '경로 설정 누락');
    const preview = within(view.getByRole('region', { name: '저장 전 미리보기' }));
    expect(preview.getAllByText('저장 경로를 복구')).toHaveLength(2);
    expect(preview.getByText('정상 열람 확인')).toBeVisible();
    expect(preview.getByText('경로 설정 누락')).toBeVisible();
    expect(preview.getByText('미확인')).toBeVisible();

    await user.selectOptions(view.getByRole('combobox', { name: '기록 유형' }), '진행 기록');
    expect(view.queryByRole('region', { name: '저장 전 미리보기' })).not.toBeInTheDocument();
    await user.click(view.getByRole('button', { name: '문의 처리 기록 저장' }));
    await waitFor(() => expect(view.getByRole('textbox', { name: '처리 내용' })).toHaveValue(''));
    const saved = await view.findByText('저장 경로를 복구', { selector: 'p' });
    expect(saved.closest('article')).toHaveTextContent('진행 기록');
    expect(saved.closest('article')).not.toHaveTextContent('경로 설정 누락');
    expect(saved.closest('article')).not.toHaveTextContent('정상 열람 확인');
  });

  it('F009-DETAIL-03 문의 요약과 추천 적용 조건을 먼저 보여주고 원문은 열 때 조회한다', async () => {
    vi.stubEnv('VITE_MOCK_EXAMPLES', 'true');
    systemMockBackend.reset();
    await login(TEST_LOGINS.network);
    const tickets = await systemGet('/tickets?scope=ASSIGNED_SYSTEMS', readTickets);
    const ticket = tickets.items.find((item) => item.title.includes('세션 오류 조사'));
    if (!ticket) throw new Error('대화가 있는 예시 티켓 필요');
    const detail = await systemGet(`/tickets/${ticket.id}`, readTicket);
    const recommended = detail.standardRequest.knowledge.find((item) => item.kind === 'CASE');
    if (!recommended) throw new Error('추천 사례 대역 필요');
    const original = systemMockBackend.request.bind(systemMockBackend);
    const request = vi
      .spyOn(systemMockBackend, 'request')
      .mockImplementation((path, method, body, token, key) =>
        path === `/api/v2/tickets/${ticket.id}/recommended-cases`
          ? [recommended]
          : original(path, method, body, token, key),
      );
    renderTickets(`/tickets/${ticket.id}`);
    const user = userEvent.setup();
    const view = within(await screen.findByRole('dialog'));
    expect(await view.findByRole('heading', { name: '문의 요약' })).toBeVisible();
    expect(view.getByText(detail.standardRequest.summary, { selector: 'p' })).toBeVisible();
    expect(view.getByText('근거·상세 보기').closest('details')).not.toHaveAttribute('open');

    const cases = view.getByRole('heading', { name: '추천 해결 사례' }).closest('section');
    if (!cases) throw new Error('추천 영역 필요');
    const condition = await within(cases).findByText(recommended.applicability);
    const card = condition.closest('details');
    if (!card) throw new Error('추천 카드 필요');
    const recommendation = within(card);
    expect(condition).toBeVisible();
    expect(card).not.toHaveAttribute('open');
    expect(recommendation.getByText(recommended.excerpt)).not.toBeVisible();
    await user.click(recommendation.getByText(recommended.title));
    expect(recommendation.getByText(recommended.excerpt)).toBeVisible();

    expect(view.queryByText('문의자')).not.toBeInTheDocument();
    expect(request.mock.calls.some(([path]) => path.includes('/related-messages'))).toBe(false);
    await user.click(view.getByText('원문 대화 보기'));
    const chat = (await view.findByText('문의자')).closest('article');
    expect(chat).toHaveAttribute('data-sender', 'USER');
    expect(view.getByText('Hops').closest('article')).toHaveAttribute('data-sender', 'AI');

    const materials = view.getByText('상담에 사용한 자료 2건');
    expect(materials.closest('details')).not.toHaveAttribute('open');
    await user.click(materials);
    expect(view.getByText(/장애 점검 안내/u)).toBeVisible();
  });

  it('F009-DETAIL-03 처리 기록 탭에서 요청 내용을 펼쳐 볼 수 있다', async () => {
    const ticket = await firstTicket();
    renderTickets(`/tickets/${ticket.id}?tab=work`);
    const user = userEvent.setup();
    const dialog = await screen.findByRole('dialog');
    const view = within(dialog);
    const summary = await view.findByText('요청 내역 참고');
    expect(view.queryByRole('heading', { name: '문의 요약' })).not.toBeInTheDocument();
    await user.click(summary);
    expect(await view.findByRole('heading', { name: '문의 요약' })).toBeVisible();
    expect(view.getByRole('textbox', { name: '처리 내용' })).toBeVisible();
  });

  it('F011-AC02 해결 사례 탭에서 같은 모달 안의 초안을 수정하고 승인한다', async () => {
    vi.stubEnv('VITE_MOCK_EXAMPLES', 'true');
    systemMockBackend.reset();
    await login(TEST_LOGINS.network);
    const tickets = await systemGet('/tickets?scope=ASSIGNED_SYSTEMS', readTickets);
    const ticket = tickets.items.find((item) => item.title.includes('첨부파일 복구 · 사례 초안'));
    if (!ticket) throw new Error('초안이 생성된 예시 티켓 필요');
    const router = renderTickets(`/tickets/${ticket.id}?tab=work`);
    const user = userEvent.setup();
    const dialog = await screen.findByRole('dialog');
    const view = within(dialog);
    expect(await view.findByText('문의자가 해결을 확인했어요.')).toBeVisible();
    expect(view.queryByRole('region', { name: '해결 사례 초안' })).not.toBeInTheDocument();

    await user.click(view.getByRole('link', { name: '해결 사례' }));
    await user.click(await view.findByRole('button', { name: '사례 초안 확인' }));
    const applicability = await view.findByRole('textbox', { name: '적용 조건' });
    expect(screen.getByRole('dialog')).toBe(dialog);
    expect(router.state.location.pathname).toBe(`/tickets/${ticket.id}`);
    expect(router.state.location.search).toBe('?tab=case');

    const updatedCondition = '전자결재에서 동일한 첨부파일 접근 오류가 발생한 경우';
    await user.clear(applicability);
    await user.type(applicability, updatedCondition);
    await user.click(view.getByRole('button', { name: '사례 검토 접기', expanded: true }));
    expect(view.queryByRole('textbox', { name: '적용 조건' })).not.toBeInTheDocument();
    await user.click(view.getByRole('button', { name: '사례 초안 확인', expanded: false }));
    expect(await view.findByRole('textbox', { name: '적용 조건' })).toHaveValue(updatedCondition);

    const sidebar = within(view.getByRole('complementary', { name: '티켓 정보와 사례 검토' }));
    const approvalHeading = sidebar.getByRole('heading', { name: '승인 전 확인' });
    expect(
      sidebar.getByRole('heading', { name: '배정 이력' }).compareDocumentPosition(approvalHeading) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    await user.click(sidebar.getByRole('button', { name: '초안 수정 저장' }));
    expect(await view.findByText('사례 문서 v2 · 원 문의 처리 기록 기반')).toBeVisible();
    expect(sidebar.getByText('초안을 저장했어요')).toBeVisible();
    await sidebar.findByText('문의자 해결 확인됨');
    await user.click(sidebar.getByRole('checkbox'));
    await user.click(sidebar.getByRole('button', { name: '확인한 버전 승인' }));
    expect(await view.findByText('승인된 사례·읽기 전용')).toBeVisible();
    expect(sidebar.getByText('사례 승인 완료')).toBeVisible();
    expect(sidebar.getByRole('region', { name: 'Vector DB 반영 상태' })).toBeVisible();
    expect(sidebar.getByRole('region', { name: 'Neo4j 반영 상태' })).toBeVisible();
    expect(view.getByRole('textbox', { name: '적용 조건' })).toHaveValue(updatedCondition);
    expect(router.state.location.pathname).toBe(`/tickets/${ticket.id}`);
  });

  it('F009-DETAIL-03 긴 설명을 문장별로 읽고 중복 없는 근거를 항목별로 펼친다', async () => {
    const ticket = await firstTicket();
    const detail = await systemGet(`/tickets/${ticket.id}`, readTicket);
    const first = '조회 기간은 최근 3개월이고 지난주에 상신했습니다.';
    const second = '상태 필터는 승인으로 선택했고 제목 키워드는 비워 두었습니다.';
    detail.standardRequest.details.taskOrScreen = '문서함 목록';
    detail.standardRequest.details.currentWorkflow = `${first} ${second}`;
    detail.standardRequest.confirmedFacts = [
      '하려던 업무·화면: 문서함 목록',
      `현재 진행 방식: ${first} ${second}`,
      '현재 진행 방식: 지난주에 직접 기안했어요.',
      '현재 진행 방식: 지난주에  직접 기안했어요.',
      '현재 진행 방식: 제출 후 다시 조회했어요.',
      '첨부 오류 안내는 따로 없었어요.',
    ].map((text) => ({ text, sourceMessageIds: [], knowledgeEvidenceIds: [] }));
    const original = systemMockBackend.request.bind(systemMockBackend);
    vi.spyOn(systemMockBackend, 'request').mockImplementation((path, method, body, token, key) =>
      path === `/api/v2/tickets/${ticket.id}` ? detail : original(path, method, body, token, key),
    );

    renderTickets(`/tickets/${ticket.id}`);
    const user = userEvent.setup();
    const view = within(await screen.findByRole('dialog'));
    expect(await view.findByText(first, { selector: 'li' })).toBeVisible();
    expect(view.getByText(second, { selector: 'li' })).toBeVisible();
    await user.click(view.getByText('근거·상세 보기'));
    const group = view.getByText('진행 방식·조건', { selector: 'summary' });
    const disclosure = group.closest('details');
    if (!disclosure) throw new Error('근거 묶음 필요');
    const evidence = within(disclosure);
    expect(evidence.getByText('2건')).toBeVisible();
    expect(evidence.getByText('지난주에 직접 기안했어요.')).not.toBeVisible();
    await user.click(group);
    expect(evidence.getByText('지난주에 직접 기안했어요.')).toBeVisible();
    expect(evidence.getByText('제출 후 다시 조회했어요.')).toBeVisible();
    expect(view.queryByText(/하려던 업무·화면: 문서함 목록/u)).not.toBeInTheDocument();
    await user.click(view.getByText('기타 확인 내용', { selector: 'summary' }));
    expect(view.getByText('첨부 오류 안내는 따로 없었어요.')).toBeVisible();
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
