import { systemGet } from '@/shared/api/systemCommands';
import type { SystemCommand } from '@/shared/api/systemCommands';
import { readCase, readCases } from '@/shared/api/systemWire';
import type {
  CaseEditCommand,
  CaseApproveCommand,
  IndexCommand,
} from '@/shared/api/types/hopsSystem1';
export const caseKeys = {
  list: (actor: string, query: string) => ['hops-system-1', 'cases', 'list', actor, query] as const,
  detail: (actor: string, id: string) => ['hops-system-1', 'cases', 'detail', actor, id] as const,
};
export const getCases = (query: string, signal?: AbortSignal) =>
  systemGet('/cases?' + query, readCases, signal);
export const getCase = (id: string, signal?: AbortSignal) =>
  systemGet(
    '/cases/' + encodeURIComponent(id),
    (raw) => {
      const result = readCase(raw);
      if (result.version.id !== id || result.publication.caseId !== id)
        throw new Error('사례 대상 불일치');
      return result;
    },
    signal,
  );
export const editCaseCommand = (id: string, body: CaseEditCommand): SystemCommand => ({
  path: '/api/v2/cases/' + encodeURIComponent(id) + '/versions',
  body,
  expectedResource: { type: 'CASE', id },
});
export const approveCaseCommand = (id: string, body: CaseApproveCommand): SystemCommand => ({
  path: '/api/v2/cases/' + encodeURIComponent(id) + '/approvals',
  body,
  expectedResource: { type: 'CASE', id },
});
export const indexCommand = (id: string, body: IndexCommand, retry: boolean): SystemCommand => ({
  path: '/api/v2/cases/' + encodeURIComponent(id) + (retry ? '/index-retries' : '/index-checks'),
  body,
  expectedResource: { type: 'CASE', id },
});
