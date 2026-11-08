import { systemGet } from '@/shared/api/systemCommands';
import { apiImageRequest } from '@/shared/api/client';
import { readInquiry, readMessages, readRun, readSystems } from '@/shared/api/systemWire';
import type { SystemCommand } from '@/shared/api/systemCommands';
import type {
  InquiryCreateCommand,
  MessageSendCommand,
  MessageReviseCommand,
  RunRetryCommand,
} from '@/shared/api/types/hopsSystem1';
export const systemChatKeys = {
  inquiry: (actor: string, id: string) => ['hops-system-1', 'chat', actor, id, 'inquiry'] as const,
  messages: (actor: string, id: string) =>
    ['hops-system-1', 'chat', actor, id, 'messages'] as const,
  run: (actor: string, id: string) => ['hops-system-1', 'chat', actor, id, 'run'] as const,
};
export const getSystemInquiry = (id: string, signal?: AbortSignal) =>
  systemGet(
    `/inquiries/${encodeURIComponent(id)}`,
    (raw) => {
      const result = readInquiry(raw);
      if (result.id !== id || (result.currentRun && result.currentRun.inquiryId !== id))
        throw new Error('문의 대상 불일치');
      return result;
    },
    signal,
  );
export const getSystemMessages = (id: string, before: number | null, signal?: AbortSignal) =>
  systemGet(
    `/inquiries/${encodeURIComponent(id)}/messages?size=30${before === null ? '' : `&beforeSequenceNo=${String(before)}`}`,
    (raw) => {
      const page = readMessages(raw);
      if (
        page.items.some(
          (message) =>
            message.inquiryId !== id || (before !== null && message.sequenceNo >= before),
        )
      )
        throw new Error('메시지 조회 범위 불일치');
      const next = page.nextBeforeSequenceNo;
      if (
        next !== null &&
        (!page.items.length ||
          next !== page.items[0]?.sequenceNo ||
          (before !== null && next >= before))
      )
        throw new Error('메시지 커서가 진행되지 않았어요.');
      return page;
    },
    signal,
  );
export const getSystemRun = (id: string, signal?: AbortSignal) =>
  systemGet(
    `/runs/${encodeURIComponent(id)}`,
    (raw) => {
      const result = readRun(raw);
      if (result.id !== id) throw new Error('실행 대상 불일치');
      return result;
    },
    signal,
  );
export const getChatSystems = (signal?: AbortSignal) => systemGet('/systems', readSystems, signal);
export const getMessageImage = (inquiryId: string, attachmentId: string, signal?: AbortSignal) =>
  apiImageRequest(
    `/api/v2/inquiries/${encodeURIComponent(inquiryId)}/image-attachments/${encodeURIComponent(attachmentId)}/content`,
    signal,
  );

export function createInquiryCommand(body: InquiryCreateCommand): SystemCommand {
  return { path: '/api/v2/inquiries', body, expectedResource: { type: 'INQUIRY' } };
}
export function sendMessageCommand(inquiryId: string, body: MessageSendCommand): SystemCommand {
  return {
    path: `/api/v2/inquiries/${encodeURIComponent(inquiryId)}/messages`,
    body,
    expectedResource: { type: 'INQUIRY', id: inquiryId },
  };
}
export function reviseMessageCommand(
  inquiryId: string,
  messageId: string,
  body: MessageReviseCommand,
): SystemCommand {
  return {
    path: `/api/v2/inquiries/${encodeURIComponent(inquiryId)}/messages/${encodeURIComponent(messageId)}/revisions`,
    body,
    expectedResource: { type: 'INQUIRY', id: inquiryId },
  };
}
export function retryRunCommand(runId: string, body: RunRetryCommand): SystemCommand {
  return {
    path: `/api/v2/runs/${encodeURIComponent(runId)}/retries`,
    body,
    expectedResource: { type: 'RUN' },
  };
}
