import { systemGet } from '@/shared/api/systemCommands';
import type { SystemCommand } from '@/shared/api/systemCommands';
import { readInquiries, readSystems, readCreation } from '@/shared/api/systemWire';
import type {
  SystemConfirmCommand,
  MessageProblemLinkCommand,
  CreationRetryCommand,
  ResolutionResponse,
} from '@/shared/api/types/hopsSystem1';

export const getSystemInquiries = (page: number, signal?: AbortSignal) =>
  systemGet(`/inquiries?page=${String(page)}&size=20`, readInquiries, signal);
export const getInquirySystems = (signal?: AbortSignal) =>
  systemGet('/systems', readSystems, signal);
export const getCreationResult = (workItemId: string, signal?: AbortSignal) =>
  systemGet(
    `/work-items/${encodeURIComponent(workItemId)}`,
    (raw) => {
      const result = readCreation(raw);
      if (result.workItemId !== workItemId) throw new Error('생성 작업 대상 불일치');
      return result;
    },
    signal,
  );
export function confirmSystemCommand(
  inquiryId: string,
  problemId: string,
  body: SystemConfirmCommand,
): SystemCommand {
  return {
    path: `/api/v2/inquiries/${encodeURIComponent(inquiryId)}/problems/${encodeURIComponent(problemId)}/system-confirmations`,
    body,
    expectedResource: { type: 'INQUIRY', id: inquiryId },
  };
}
export function linkProblemsCommand(
  inquiryId: string,
  messageId: string,
  body: MessageProblemLinkCommand,
): SystemCommand {
  return {
    path: `/api/v2/inquiries/${encodeURIComponent(inquiryId)}/messages/${encodeURIComponent(messageId)}/problem-links`,
    body,
    expectedResource: { type: 'INQUIRY', id: inquiryId },
  };
}
export function retryCreationCommand(
  workItemId: string,
  body: CreationRetryCommand,
): SystemCommand {
  return {
    path: `/api/v2/work-items/${encodeURIComponent(workItemId)}/retries`,
    body,
    expectedResource: { type: 'WORK_ITEM', id: workItemId },
  };
}
export function resolutionResponseCommand(id: string, body: ResolutionResponse): SystemCommand {
  return {
    path: '/api/v2/resolution-requests/' + encodeURIComponent(id) + '/responses',
    body,
    expectedResource: { type: 'RESOLUTION_REQUEST', id },
  };
}
