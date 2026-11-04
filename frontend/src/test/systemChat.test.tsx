import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { useSession } from '@/features/auth';
import * as api from '@/features/chat/api/systemChatApi';
import { useSystemChat } from '@/features/chat/hooks/useSystemChat';
import type { ChatMessage, InquiryResult, RunSummary } from '@/shared/api/types/hopsSystem1';

vi.mock('@/features/realtime', () => ({
  useSubscription: () => ({ denied: false, state: 'connected' }),
  ConnectionIndicator: () => null,
}));
vi.mock('@/shared/realtime', () => ({ realtime: { on: () => () => undefined } }));

const inquiryId = crypto.randomUUID();
const running: RunSummary = {
  id: crypto.randomUUID(),
  inquiryId,
  inquiryVersion: 1,
  answerMessageId: crypto.randomUUID(),
  status: 'RUNNING',
  error: null,
};
const snapshot = (run: RunSummary): InquiryResult => ({
  id: inquiryId,
  inquiryNumber: 'INQ-TEST',
  inquiryVersion: run.inquiryVersion,
  status: 'ANALYZING',
  problems: [],
  currentRun: run,
  pendingTargetMessageIds: [],
  resultRevision: 1,
  createdAt: '2026-11-01T00:00:00Z',
  updatedAt: '2026-11-01T00:00:00Z',
});
const answer: ChatMessage = {
  id: running.answerMessageId,
  inquiryId,
  requestId: null,
  revision: 1,
  sequenceNo: 2,
  sender: 'AI',
  content: '서버에 저장된 답변',
  problemIds: [],
  revisions: [],
  createdAt: '2026-11-01T00:00:00Z',
};
let client: QueryClient;
const actor = 'synthetic-actor';
function wrapper({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}
beforeEach(() => {
  useSession.setState({
    user: {
      id: actor,
      name: '가상 사용자',
      employeeNo: 'TEST',
      roles: [],
      primarySystemIds: [],
      department: {
        id: 'synthetic-department',
        code: 'TEST',
        name: '가상 부서',
      },
    },
  });
  client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  vi.spyOn(api, 'getSystemInquiry').mockResolvedValue(snapshot(running));
  vi.spyOn(api, 'getSystemRun').mockResolvedValue(running);
  vi.spyOn(api, 'getSystemMessages').mockResolvedValue({ items: [], nextBeforeSequenceNo: null });
});
afterEach(() => {
  cleanup();
  client.clear();
  vi.restoreAllMocks();
  vi.unstubAllEnvs();
});

it('F003-CONTRACT-09 문의 GET의 종료 상태가 캐시된 RUNNING보다 우선하며 최종 메시지를 다시 읽는다', async () => {
  const { result } = renderHook(() => useSystemChat(inquiryId), { wrapper });
  await waitFor(() => {
    expect(result.current.runQuery.data?.status).toBe('RUNNING');
  });
  const finished = { ...running, status: 'SUCCEEDED' };
  vi.mocked(api.getSystemInquiry).mockResolvedValue(snapshot(finished));
  vi.mocked(api.getSystemMessages).mockResolvedValue({
    items: [answer],
    nextBeforeSequenceNo: null,
  });
  await act(async () => {
    await result.current.inquiry.refetch();
  });
  await waitFor(() => {
    expect(result.current.active).toBe(false);
    expect(result.current.run?.status).toBe('SUCCEEDED');
    expect(result.current.items).toEqual([answer]);
  });
});

it('F003-CONTRACT-09 currentRun 교체 시 직전 답변도 조회하고 새 실행을 읽는다', async () => {
  const { result } = renderHook(() => useSystemChat(inquiryId), { wrapper });
  await waitFor(() => {
    expect(result.current.runQuery.data).toEqual(running);
  });
  const next = {
    ...running,
    id: crypto.randomUUID(),
    inquiryVersion: 2,
    answerMessageId: crypto.randomUUID(),
    status: 'QUEUED',
  };
  vi.mocked(api.getSystemInquiry).mockResolvedValue(snapshot(next));
  vi.mocked(api.getSystemRun).mockResolvedValue(next);
  vi.mocked(api.getSystemMessages).mockResolvedValue({
    items: [answer],
    nextBeforeSequenceNo: null,
  });
  await act(async () => {
    await result.current.inquiry.refetch();
  });
  await waitFor(() => {
    expect(api.getSystemRun).toHaveBeenCalledWith(next.id, expect.any(AbortSignal));
    expect(result.current.run?.id).toBe(next.id);
    expect(result.current.items).toEqual([answer]);
    expect(result.current.live).toBeNull();
  });
});

it('F004-CONTRACT-01 겹친 메시지 페이지는 높은 revision을 표시한다', async () => {
  const revised = { ...answer, revision: 2, content: '정정된 서버 답변' };
  client.setQueryData(api.systemChatKeys.messages(actor, inquiryId), {
    pages: [
      { items: [revised], nextBeforeSequenceNo: 2 },
      { items: [answer], nextBeforeSequenceNo: null },
    ],
    pageParams: [null, 2],
  });
  vi.mocked(api.getSystemMessages).mockImplementation((_id, before) =>
    Promise.resolve({
      items: [before === null ? revised : answer],
      nextBeforeSequenceNo: before === null ? 2 : null,
    }),
  );
  const { result } = renderHook(() => useSystemChat(inquiryId), { wrapper });
  await waitFor(() => {
    expect(result.current.items).toEqual([revised]);
  });
});
