import { apiRequest } from '@/shared/api/client';
import type { InquiryResult, MessagePage, RunSummary } from '@/shared/api/types';

export const chatKeys = {
  all: ['chat'] as const,
  inquiry: (id: string) => ['chat', id, 'inquiry'] as const,
  messages: (id: string) => ['chat', id, 'messages'] as const,
  run: (id: string) => ['chat', 'run', id] as const,
};
export const getInquiry = (id: string) =>
  apiRequest<InquiryResult>(`/api/inquiries/${encodeURIComponent(id)}`);
export async function getMessages(id: string, after = 0, signal?: AbortSignal) {
  const page = await apiRequest<MessagePage>(
    `/api/inquiries/${encodeURIComponent(id)}/messages?afterSequenceNo=${String(after)}&size=20`,
    { signal },
  );
  const next = page.nextAfterSequenceNo;
  if (next !== null && (!Number.isSafeInteger(next) || next <= after || page.items.length === 0)) {
    throw new Error('대화 조회 위치가 진행되지 않았어요. 다시 조회해 주세요.');
  }
  return page;
}
export const getRun = (id: string) => apiRequest<RunSummary>(`/api/runs/${encodeURIComponent(id)}`);
