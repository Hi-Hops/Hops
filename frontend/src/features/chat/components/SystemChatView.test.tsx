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

// 분석 상태 변화를 rerender로 재현하므로 Provider를 wrapper로 유지한다.
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
  vi.unstubAllEnvs();
  localStorage.clear();
});

it('이미지 기능이 켜진 경우에만 관찰 확인을 표시한다', () => {
  const observation = {
    observationId: crypto.randomUUID(),
    observationRevision: 1,
    attachmentId: crypto.randomUUID(),
    problemId,
    text: '오류 화면',
    state: 'UNCONFIRMED' as const,
  };
  const state = chatState(1, false, []);
  vi.mocked(useSystemChat).mockReturnValue({
    ...state,
    items: [{ ...answer, imageObservationQuestions: [observation] }],
  });
  vi.stubEnv('VITE_CHAT_IMAGE_ATTACHMENTS', 'false');
  const view = render(<SystemChatView inquiryId={inquiryId} />, { wrapper: Providers });
  expect(screen.queryByRole('region', { name: '이미지 관찰 확인' })).not.toBeInTheDocument();
  vi.stubEnv('VITE_CHAT_IMAGE_ATTACHMENTS', 'true');
  view.rerender(<SystemChatView inquiryId={inquiryId} />);
  expect(screen.getByRole('region', { name: '이미지 관찰 확인' })).toHaveTextContent('오류 화면');
});

it('F005-CONTRACT-03 분석이 시작·종료돼도 닫은 접수 결과의 모달은 다시 열지 않는다', async () => {
  vi.mocked(useSystemChat).mockReturnValue(chatState(1, false));
  const view = render(<SystemChatView inquiryId={inquiryId} />, { wrapper: Providers });
  await userEvent.click(await screen.findByRole('button', { name: '닫기' }));
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

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
  const panel = screen.getByRole('region', { name: '문제별 처리 결과', hidden: true });
  expect(within(panel).getByText('티켓 없음')).toBeInTheDocument();
});

it('F005-DETAIL-05 긴 티켓 번호는 앞자리 0을 줄여 표시하고 전체 번호를 title에 남긴다', async () => {
  if (!problem.ticket) throw new Error('가상 티켓 없음');
  const fullNumber = 'T-0000000000000123';
  const long = { ...problem, ticket: { ...problem.ticket, ticketNumber: fullNumber } };
  vi.mocked(useSystemChat).mockReturnValue(chatState(1, false, [long]));
  render(<SystemChatView inquiryId={inquiryId} />, { wrapper: Providers });
  const dialog = await screen.findByRole('dialog', { name: '티켓 접수 결과' });
  expect(within(dialog).getByText('T-0123')).toHaveAttribute('title', fullNumber);
  const panel = screen.getByRole('region', { name: '문제별 처리 결과', hidden: true });
  expect(within(panel).getByText('T-0123')).toHaveAttribute('title', fullNumber);
  expect(screen.queryByText(fullNumber)).not.toBeInTheDocument();
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

it('F003-DETAIL-11 처리 현황은 대화와 분리되고 문의자가 처리할 문제를 먼저 표시한다', async () => {
  const waiting: Problem = {
    ...problem,
    id: crypto.randomUUID(),
    summary: '가상 공유 폴더 접근 불가',
    state: 'WAITING_USER',
    questionCount: 1,
    creationResult: null,
    ticket: null,
  };
  vi.mocked(useSystemChat).mockReturnValue(chatState(0, false, [problem, waiting]));
  render(<SystemChatView inquiryId={inquiryId} />, { wrapper: Providers });
  await userEvent.click(await screen.findByRole('button', { name: '닫기' }));
  const rail = screen.getByRole('complementary', { name: '처리 현황' });
  const conversation = screen.getByRole('region', { name: '대화' });
  expect(within(conversation).queryByText('TK-TEST')).not.toBeInTheDocument();
  const [first, second] = within(rail).getAllByRole('listitem');
  if (!first || !second) throw new Error('문제 항목 없음');
  expect(first).toHaveAccessibleName(waiting.summary);
  expect(second).toHaveAccessibleName(problem.summary);

  // 패널이 들어가지 않는 좁은 화면의 요약 줄은 같은 내용을 모달로 연다.
  await userEvent.click(
    within(conversation).getByRole('button', { name: /처리 현황 · 문제 2건.*질문 1\/5/u }),
  );
  const dialog = await screen.findByRole('dialog', { name: '처리 현황' });
  expect(within(dialog).getByText('TK-TEST')).toBeVisible();
  expect(within(dialog).getByText('질문 1/5')).toBeVisible();
});

it('F003-DETAIL-11 문제가 나뉘기 전에는 처리 현황에 빈 상태를 안내하고 요약 줄을 표시하지 않는다', () => {
  vi.mocked(useSystemChat).mockReturnValue(chatState(0, true, []));
  render(<SystemChatView inquiryId={inquiryId} />, { wrapper: Providers });
  expect(
    within(screen.getByRole('complementary', { name: '처리 현황' })).getByText(
      '문제를 확인하면 진행 상태가 여기에 표시돼요.',
    ),
  ).toBeVisible();
  expect(screen.queryByRole('button', { name: /처리 현황/u })).not.toBeInTheDocument();
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
