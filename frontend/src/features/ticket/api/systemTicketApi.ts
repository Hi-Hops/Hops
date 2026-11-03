import { systemGet } from '@/shared/api/systemCommands';
import {
  readTickets,
  readTicket,
  readManagementTicket,
  readRelated,
  readSystems,
  readPrimary,
  readResolution,
  readGeneration,
} from '@/shared/api/systemWire';
import type { SystemCommand } from '@/shared/api/systemCommands';
import type {
  WorkRecordCommand,
  AssignmentCorrectionCommand,
  ProcessingTypeCorrectionCommand,
} from '@/shared/api/types/hopsSystem1';
export const systemTicketKeys = {
  list: (actor: string, query: string) =>
    ['hops-system-1', 'tickets', 'list', actor, query] as const,
  detail: (actor: string, id: string, admin: boolean) =>
    ['hops-system-1', 'tickets', 'detail', actor, id, admin] as const,
};
export const getSystems = (signal?: AbortSignal) => systemGet('/systems', readSystems, signal);
export const getSystemTickets = (query: string, signal?: AbortSignal) =>
  systemGet(`/tickets?${query}`, readTickets, signal);
export const getSystemTicket = (id: string, signal?: AbortSignal) =>
  systemGet(
    `/tickets/${encodeURIComponent(id)}`,
    (raw) => {
      const result = readTicket(raw);
      if (result.id !== id) throw new Error('티켓 대상 불일치');
      return result;
    },
    signal,
  );
export const getManagementTicket = (id: string, signal?: AbortSignal) =>
  systemGet(
    `/management/tickets/${encodeURIComponent(id)}`,
    (raw) => {
      const result = readManagementTicket(raw);
      if (result.id !== id) throw new Error('관리 티켓 대상 불일치');
      return result;
    },
    signal,
  );
export const getRelatedMessages = (id: string, cursor: string | null, signal?: AbortSignal) =>
  systemGet(
    `/tickets/${encodeURIComponent(id)}/related-messages?size=30${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`,
    (raw) => {
      const page = readRelated(raw);
      if (page.nextCursor !== null && page.nextCursor === cursor)
        throw new Error('관련 대화 커서가 진행되지 않았어요.');
      return page;
    },
    signal,
  );
export const getPrimary = (systemId: string, signal?: AbortSignal) =>
  systemGet(
    '/systems/' + encodeURIComponent(systemId) + '/primary',
    (raw) => {
      const result = readPrimary(raw);
      if (result.systemId !== systemId) throw new Error('담당 시스템 불일치');
      return result;
    },
    signal,
  );
export const getResolution = (id: string, signal?: AbortSignal) =>
  systemGet(
    '/resolution-requests/' + encodeURIComponent(id),
    (raw) => {
      const result = readResolution(raw);
      if (result.id !== id) throw new Error('해결 확인 대상 불일치');
      return result;
    },
    signal,
  );
export const getGeneration = (id: string, signal?: AbortSignal) =>
  systemGet(
    '/case-generations/' + encodeURIComponent(id),
    (raw) => {
      const result = readGeneration(raw);
      if (result.id !== id) throw new Error('초안 생성 대상 불일치');
      return result;
    },
    signal,
  );
export function workRecordCommand(id: string, body: WorkRecordCommand): SystemCommand {
  return {
    path: '/api/v2/tickets/' + encodeURIComponent(id) + '/work-records',
    body,
    expectedResource: { type: 'TICKET', id },
  };
}
export function assignmentCommand(id: string, body: AssignmentCorrectionCommand): SystemCommand {
  return {
    path: '/api/v2/tickets/' + encodeURIComponent(id) + '/assignment-corrections',
    body,
    expectedResource: { type: 'TICKET', id },
  };
}
export function processingCommand(
  id: string,
  body: ProcessingTypeCorrectionCommand,
): SystemCommand {
  return {
    path: '/api/v2/tickets/' + encodeURIComponent(id) + '/processing-type-corrections',
    body,
    expectedResource: { type: 'TICKET', id },
  };
}
export function generationRetryCommand(id: string, requestId: string): SystemCommand {
  return {
    path: '/api/v2/case-generations/' + encodeURIComponent(id) + '/retries',
    body: { requestId },
    expectedResource: { type: 'CASE_GENERATION', id },
  };
}
