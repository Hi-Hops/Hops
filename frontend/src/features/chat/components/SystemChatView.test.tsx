import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useState } from 'react';
import type { ReactNode } from 'react';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { useSession } from '@/features/auth';
import { systems } from '@/shared/api/systemMockData';
import type { ChatMessage, InquiryResult, Problem } from '@/shared/api/types/hopsSystem1';
import { useSystemChat } from '../hooks/useSystemChat';
import { SystemChatView } from './SystemChatView';

vi.mock('../hooks/useSystemChat', () => ({ useSystemChat: vi.fn() }));

const inquiryId = crypto.randomUUID();
const problemId = crypto.randomUUID();
const system = systems[0];
const person = {
  id: crypto.randomUUID(),
  name: '가상 담당자',
  department: { id: crypto.randomUUID(), code: 'TEST', name: '가상 부서' },
};
const problem: Problem = {
  id: problemId,
  inquiryId,
  summary: '가상 결재 문서 오류',
  intent: 'INCIDENT',
  processingType: 'INCIDENT',
  reportedSystemId: system.id,
  reportedFeatureId: null,
  state: 'TICKET_CREATED',
  questionCount: 0,
  guide: null,
  creationResult: {
    workItemId: crypto.randomUUID(),
    problemId,
    inquiryVersion: 1,
    outcome: 'STORED',
    ticketId: crypto.randomUUID(),
    error: null,
    updatedAt: '2026-11-01T00:00:00Z',
  },
  ticket: {
    id: crypto.randomUUID(),
    inquiryId,
    problemId,
    workItemId: crypto.randomUUID(),
    ticketNumber: 'TK-TEST',
    title: '가상 결재 문서 오류',
    processingType: 'INCIDENT',
    reportedSystem: system,
    responsibleSystem: system,
    assignee: person,
    state: 'WAITING',
    ticketRevision: 1,
    createdAt: '2026-11-01T00:00:00Z',
    updatedAt: '2026-11-01T00:00:00Z',
  },
  activeResolutionRequest: null,
};
const answer: ChatMessage = {
  id: crypto.randomUUID(),
  inquiryId,
  requestId: null,
  revision: 1,
  sequenceNo: 2,
  sender: 'AI',
  content: '가상 접수 결과 답변',
  problemIds: [problemId],
  revisions: [],
  createdAt: '2026-11-01T00:00:00Z',
};
const inquiry = (resultRevision: number, problems = [problem]): InquiryResult => ({
  id: inquiryId,
  inquiryNumber: 'INQ-TEST',
  inquiryVersion: 1,
  status: 'IN_PROGRESS',
  problems,
  currentRun: null,
  pendingTargetMessageIds: [],
  resultRevision,
  createdAt: '2026-11-01T00:00:00Z',
  updatedAt: '2026-11-01T00:00:00Z',
});
function chatState(resultRevision: number, active: boolean, problems = [problem]) {
  return {
    inquiry: {
      data: inquiry(resultRevision, problems),
      isPending: false,
      isError: false,
      error: null,
    },
    messages: { error: null, isError: false, hasNextPage: false, isFetching: false },
    runQuery: { error: null, isError: false },
    subscription: { denied: false },
    items: [answer],
    live: active ? { runId: 'run', text: '' } : null,
    trails: {},
    active,
    run: null,
    ready: !active,
  } as unknown as ReturnType<typeof useSystemChat>;
}

// 결과 카드 이동을 rerender로 재현하므로 Provider를 wrapper로 유지한다.
function Providers({ children }: { children: ReactNode }) {
  const [client] = useState(
    () => new QueryClient({ defaultOptions: { queries: { retry: false } } }),
  );
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter>{children}</MemoryRouter>
    </QueryClientProvider>
  );
}

beforeEach(() => {
  useSession.setState({
    user: {
      id: 'synthetic-actor',
      name: '가상 문의자',
      employeeNo: 'TEST',
      roles: [],
      primarySystemIds: [],
      department: { id: 'synthetic-department', code: 'TEST', name: '가상 부서' },
    },
  });
});
afterEach(() => {
  vi.mocked(useSystemChat).mockReset();
  localStorage.clear();
});

it('F005-CONTRACT-03 결과 카드가 분석 시작·종료로 자리를 옮겨도 닫은 접수 결과의 접수 결과 모달은 다시 열지 않는다', async () => {
  vi.mocked(useSystemChat).mockReturnValue(chatState(1, false));
  const view = render(<SystemChatView inquiryId={inquiryId} />, { wrapper: Providers });
  await userEvent.click(await screen.findByRole('button', { name: '닫기' }));
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

  // 분석이 시작되면 결과 카드는 진행 표시 아래로, 끝나면 다시 답변 아래로 옮겨진다.
  vi.mocked(useSystemChat).mockReturnValue(chatState(1, true));
  view.rerender(<SystemChatView inquiryId={inquiryId} />);
  vi.mocked(useSystemChat).mockReturnValue(chatState(1, false));
  view.rerender(<SystemChatView inquiryId={inquiryId} />);
  expect(screen.getByRole('region', { name: '문제별 처리 결과' })).toBeInTheDocument();
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

  vi.mocked(useSystemChat).mockReturnValue(chatState(2, false));
  view.rerender(<SystemChatView inquiryId={inquiryId} />);
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

  if (!problem.ticket) throw new Error('가상 티켓 없음');
  for (const state of ['IN_PROGRESS', 'WAITING_CONFIRMATION', 'RESOLVED'] as const) {
    const changed = { ...problem, ticket: { ...problem.ticket, state, ticketRevision: 5 } };
    vi.mocked(useSystemChat).mockReturnValue(chatState(5, false, [changed]));
    view.rerender(<SystemChatView inquiryId={inquiryId} />);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  }

  // 이후 다른 문제의 새 접수 결과가 생기면 다시 안내한다.
  const another = { ...problem, id: crypto.randomUUID() };
  vi.mocked(useSystemChat).mockReturnValue(chatState(3, false, [problem, another]));
  view.rerender(<SystemChatView inquiryId={inquiryId} />);
  expect(await screen.findByRole('dialog', { name: '티켓 접수 결과' })).toBeVisible();
});

it('F005-CONTRACT-03 안내한 접수 결과의 모달은 새로고침·재진입해도 다시 열지 않는다', async () => {
  vi.mocked(useSystemChat).mockReturnValue(chatState(1, false));
  const first = render(<SystemChatView inquiryId={inquiryId} />, { wrapper: Providers });
  expect(await screen.findByRole('dialog', { name: '티켓 접수 결과' })).toBeVisible();

  // 닫지 않고 새로고침해도 이미 안내한 결과로 기록돼 있다.
  first.unmount();
  const second = render(<SystemChatView inquiryId={inquiryId} />, { wrapper: Providers });
  expect(await screen.findByRole('region', { name: '문제별 처리 결과' })).toBeInTheDocument();
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

  // 다른 소유자의 기록과는 분리한다.
  second.unmount();
  useSession.setState((state) => ({
    user: state.user ? { ...state.user, id: 'other-user' } : null,
  }));
  render(<SystemChatView inquiryId={inquiryId} />, { wrapper: Providers });
  expect(await screen.findByRole('dialog', { name: '티켓 접수 결과' })).toBeVisible();
});

it('F005-DETAIL-05 접수 결과 모달은 티켓 번호·책임 시스템·담당자와 미생성 항목을 구분해 보여준다', async () => {
  const failed: Problem = {
    ...problem,
    id: crypto.randomUUID(),
    summary: '가상 미생성 문제',
    ticket: null,
    creationResult: problem.creationResult && {
      ...problem.creationResult,
      outcome: 'NOT_CREATED',
      ticketId: null,
    },
  };
  vi.mocked(useSystemChat).mockReturnValue(chatState(1, false, [problem, failed]));
  render(<SystemChatView inquiryId={inquiryId} />, { wrapper: Providers });
  const dialog = await screen.findByRole('dialog', { name: '티켓 접수 결과' });
  const [stored, missing] = within(dialog).getAllByRole('listitem');
  if (!stored || !missing) throw new Error('결과 항목 없음');
  expect(within(stored).getByText('TK-TEST')).toBeVisible();
  expect(within(stored).getByText('처리 대기')).toBeVisible();
  expect(within(stored).getByText(person.name)).toBeVisible();
  expect(within(missing).getByText('미생성 확인')).toBeVisible();
  expect(within(dialog).getByText(/1건은 저장하지 못했어요/)).toBeVisible();
});

it('F005-DETAIL-02 보고 시스템과 책임 시스템이 다른 저장 티켓은 결과 카드에 두 시스템을 모두 표시한다', async () => {
  const [reported, responsible] = systems;
  if (!problem.ticket) throw new Error('가상 티켓 없음');
  const moved: Problem = {
    ...problem,
    ticket: { ...problem.ticket, reportedSystem: reported, responsibleSystem: responsible },
  };
  vi.mocked(useSystemChat).mockReturnValue(chatState(1, false, [moved]));
  render(<SystemChatView inquiryId={inquiryId} />, { wrapper: Providers });
  await userEvent.click(await screen.findByRole('button', { name: '닫기' }));
  const row = within(screen.getByRole('region', { name: '문제별 처리 결과' })).getByRole(
    'listitem',
    { name: moved.summary },
  );
  expect(within(row).getByText(new RegExp(`보고 시스템 ${reported.name}`, 'u'))).toBeVisible();
  expect(within(row).getByText(`책임 ${responsible.name}`)).toBeVisible();
});

it('분석이 끝나 답변이 저장돼도 진행 단계는 해당 답변 위에 완료 기록으로 남는다', () => {
  vi.mocked(useSystemChat).mockReturnValue({
    ...chatState(1, false, []),
    trails: { [answer.id]: [{ stepNo: 1, text: '가상 문의 내용을 확인했어요' }] },
  });
  render(<SystemChatView inquiryId={inquiryId} />, { wrapper: Providers });
  const article = screen.getByRole('article', { name: 'Hops 답변' });
  const steps = within(article).getByRole('list', { name: '완료된 진행 단계' });
  expect(within(steps).getByText('가상 문의 내용을 확인했어요')).toBeVisible();
  expect(screen.queryByRole('status')).not.toBeInTheDocument();
});
