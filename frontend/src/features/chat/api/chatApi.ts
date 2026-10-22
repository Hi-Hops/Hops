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
export const getMessages = (id: string, after = 0) =>
  apiRequest<MessagePage>(
    `/api/inquiries/${encodeURIComponent(id)}/messages?afterSequenceNo=${String(after)}&size=20`,
  );
export const getRun = (id: string) => apiRequest<RunSummary>(`/api/runs/${encodeURIComponent(id)}`);
