import { apiRequest } from '@/shared/api/client';
import type {
  InquiryResult,
  MessageHistoryPage,
  RunRetryRequest,
  RunSummary,
} from '@/shared/api/types';

export const chatKeys = {
  all: ['chat'] as const,
  inquiry: (id: string) => ['chat', id, 'inquiry'] as const,
  messages: (id: string) => ['chat', id, 'messages'] as const,
  run: (id: string) => ['chat', 'run', id] as const,
};
export const getInquiry = (id: string) =>
  apiRequest<InquiryResult>(`/api/inquiries/${encodeURIComponent(id)}`);
export async function getMessages(id: string, before: number | null = null, signal?: AbortSignal) {
  const page = await apiRequest<MessageHistoryPage>(
    `/api/inquiries/${encodeURIComponent(id)}/messages?size=30${before === null ? '' : `&beforeSequenceNo=${String(before)}`}`,
    { signal },
  );
  const next = page.nextBeforeSequenceNo;
  if (
    next !== null &&
    (!Number.isSafeInteger(next) ||
      next < 1 ||
      (before !== null && next >= before) ||
      page.items.length === 0 ||
      next !== page.items[0]?.sequenceNo)
  ) {
    throw new Error('대화 조회 위치가 진행되지 않았어요. 다시 조회해 주세요.');
  }
  return page;
}
export const retryRun = (id: string, request: RunRetryRequest) =>
  apiRequest<RunSummary>(`/api/runs/${encodeURIComponent(id)}/retries`, {
    method: 'POST',
    body: request,
  });
export const getRun = (id: string) => apiRequest<RunSummary>(`/api/runs/${encodeURIComponent(id)}`);
