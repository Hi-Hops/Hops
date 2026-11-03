// @vitest-environment node
import { createServer } from 'node:http';
import type { IncomingMessage, ServerResponse } from 'node:http';
import type { AddressInfo } from 'node:net';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiRequest, registerTokenProvider } from '@/shared/api/client';
import { savedResource, sendSystemCommand } from '@/shared/api/systemCommands';
import { SseTransport } from '@/shared/realtime/sseTransport';
import type { CommandReceipt } from '@/shared/api/types/hopsSystem1';
import {
  createInquiryCommand,
  sendMessageCommand,
  reviseMessageCommand,
  retryRunCommand,
  getSystemInquiry,
  getSystemMessages,
  getSystemRun,
} from '@/features/chat/api/systemChatApi';
import {
  confirmSystemCommand,
  linkProblemsCommand,
  retryCreationCommand,
  getCreationResult,
  getSystemInquiries,
  getInquirySystems,
  resolutionResponseCommand,
} from '@/features/inquiry/api/systemInquiryApi';
import {
  getSystemTickets,
  getManagementTicket,
  getSystemTicket,
  getRelatedMessages,
  workRecordCommand,
  assignmentCommand,
  processingCommand,
  generationRetryCommand,
  getPrimary,
  getResolution,
  getGeneration,
} from '@/features/ticket/api/systemTicketApi';
import {
  editCaseCommand,
  approveCaseCommand,
  indexCommand,
  getCases,
  getCase,
} from '@/features/case/api/caseApi';
import type { SystemCommand } from '@/shared/api/systemCommands';
import { login, logout, useSession } from '@/features/auth';
import { systems, users } from '@/shared/api/systemMockData';

let handle: (req: IncomingMessage, res: ServerResponse) => void;
const server = createServer((req, res) => {
  handle(req, res);
});
let peers: ServerResponse[];
let transport: SseTransport | null;
beforeEach(async () => {
  vi.stubEnv('VITE_DATA_MODE', 'live');
  registerTokenProvider(() => 'synthetic-access');
  peers = [];
  transport = null;
  handle = (_req, res) => {
    res.writeHead(503).end();
  };
  await new Promise<void>((resolve) => {
    server.listen(0, '127.0.0.1', resolve);
  });
  vi.stubEnv('VITE_API_BASE_URL', `http://127.0.0.1:${(server.address() as AddressInfo).port}`);
});
afterEach(async () => {
  transport?.disconnect();
  peers.forEach((res) => {
    res.end();
  });
  server.closeAllConnections();
  await new Promise<void>((resolve) => {
    server.close(() => {
      resolve();
    });
  });
  vi.unstubAllEnvs();
  useSession.getState().logout();
});
function json(res: ServerResponse, status: number, body: unknown) {
  res.writeHead(status, { 'Content-Type': 'application/json' }).end(JSON.stringify(body));
}
function receipt(state: CommandReceipt['state'], requestId: string): CommandReceipt {
  return {
    requestId,
    operationId: crypto.randomUUID(),
    state,
    resource: state === 'SUCCEEDED' ? { type: 'INQUIRY', id: crypto.randomUUID() } : null,
    error: null,
  };
}
function error(code: string) {
  return {
    code,
    message: '안전한 오류 안내',
    requestId: crypto.randomUUID(),
    retryable: false,
    fieldErrors: [],
  };
}

describe('hops-system-1 실제 HTTP/SSE 대역 계약', () => {
  it('v2 로그인·내 정보·Access 만료 1회 갱신·로그아웃을 실제 HTTP로 연결한다', async () => {
    registerTokenProvider(() => useSession.getState().token);
    const seen: { path: string | undefined; authorization: string | undefined }[] = [];
    handle = (req, res) => {
      seen.push({ path: req.url, authorization: req.headers.authorization });
      if (req.url === '/api/v2/auth/logout') {
        res.writeHead(204).end();
        return;
      }
      if (req.url === '/api/v2/auth/me') {
        json(res, 200, users[1]);
        return;
      }
      if (req.url === '/api/v2/systems') {
        if (req.headers.authorization === 'Bearer synthetic-initial')
          json(res, 401, error('ACCESS_TOKEN_EXPIRED'));
        else json(res, 200, systems);
        return;
      }
      json(res, 200, {
        accessToken:
          req.url === '/api/v2/auth/refresh' ? 'synthetic-refreshed' : 'synthetic-initial',
        expiresAt: '2027-01-01T00:00:00Z',
        user: users[1],
      });
    };
    const user = await login({ loginId: 'synthetic', password: 'synthetic' });
    expect(user.primarySystemIds).toEqual(users[1].primarySystemIds);
    expect(await getInquirySystems()).toEqual(systems);
    await logout();
    expect(useSession.getState().user).toBeNull();
    expect(seen).toEqual([
      { path: '/api/v2/auth/login', authorization: undefined },
      { path: '/api/v2/auth/me', authorization: 'Bearer synthetic-initial' },
      { path: '/api/v2/systems', authorization: 'Bearer synthetic-initial' },
      { path: '/api/v2/auth/refresh', authorization: undefined },
      { path: '/api/v2/systems', authorization: 'Bearer synthetic-refreshed' },
      { path: '/api/v2/auth/logout', authorization: 'Bearer synthetic-refreshed' },
    ]);
  });
  it('화면의 모든 변경 API가 v2 경로·본문·Bearer·원 요청 키로 실제 HTTP를 호출한다', async () => {
    const inquiryId = crypto.randomUUID();
    const messageId = crypto.randomUUID();
    const problemId = crypto.randomUUID();
    const runId = crypto.randomUUID();
    const systemId = crypto.randomUUID();
    const ticketId = crypto.randomUUID();
    const caseId = crypto.randomUUID();
    const makeId = () => crypto.randomUUID();
    const commands: [SystemCommand, string, Record<string, unknown>][] = [
      [
        createInquiryCommand({ requestId: makeId(), content: '최초 문의', reportedSystemId: null }),
        '/inquiries',
        { content: '최초 문의', reportedSystemId: null },
      ],
      [
        sendMessageCommand(inquiryId, {
          requestId: makeId(),
          content: '보완',
          expectedInquiryVersion: 2,
          problemIds: [problemId],
        }),
        `/inquiries/${inquiryId}/messages`,
        { content: '보완', expectedInquiryVersion: 2, problemIds: [problemId] },
      ],
      [
        reviseMessageCommand(inquiryId, messageId, {
          requestId: makeId(),
          content: '정정',
          expectedInquiryVersion: 3,
          expectedMessageRevision: 1,
        }),
        `/inquiries/${inquiryId}/messages/${messageId}/revisions`,
        { content: '정정', expectedInquiryVersion: 3, expectedMessageRevision: 1 },
      ],
      [
        confirmSystemCommand(inquiryId, problemId, {
          requestId: makeId(),
          expectedInquiryVersion: 4,
          systemId,
        }),
        `/inquiries/${inquiryId}/problems/${problemId}/system-confirmations`,
        { expectedInquiryVersion: 4, systemId },
      ],
      [
        linkProblemsCommand(inquiryId, messageId, {
          requestId: makeId(),
          expectedInquiryVersion: 5,
          problemIds: [problemId],
        }),
        `/inquiries/${inquiryId}/messages/${messageId}/problem-links`,
        { expectedInquiryVersion: 5, problemIds: [problemId] },
      ],
      [
        retryRunCommand(runId, { requestId: makeId(), expectedInquiryVersion: 6 }),
        `/runs/${runId}/retries`,
        { expectedInquiryVersion: 6 },
      ],
      [
        retryCreationCommand(problemId, { requestId: makeId(), expectedInquiryVersion: 6 }),
        `/work-items/${problemId}/retries`,
        { expectedInquiryVersion: 6 },
      ],
      [
        resolutionResponseCommand(problemId, {
          requestId: makeId(),
          expectedRequestRevision: 1,
          decision: 'RESOLVED',
        }),
        '/resolution-requests/' + problemId + '/responses',
        { expectedRequestRevision: 1, decision: 'RESOLVED' },
      ],
      [
        workRecordCommand(ticketId, {
          requestId: makeId(),
          expectedTicketRevision: 4,
          kind: 'VERIFIED_RESOLUTION',
          action: '실제 조치',
          verification: '실제 검증',
          confirmedCause: null,
          appliesToSystemVersion: null,
        }),
        '/tickets/' + ticketId + '/work-records',
        {
          expectedTicketRevision: 4,
          kind: 'VERIFIED_RESOLUTION',
          action: '실제 조치',
          verification: '실제 검증',
          confirmedCause: null,
          appliesToSystemVersion: null,
        },
      ],
      [
        assignmentCommand(ticketId, {
          requestId: makeId(),
          expectedTicketRevision: 5,
          responsibleSystemId: systemId,
          reason: '책임 시스템 정정',
          evidenceMessageIds: [messageId],
          evidenceNote: null,
        }),
        '/tickets/' + ticketId + '/assignment-corrections',
        {
          expectedTicketRevision: 5,
          responsibleSystemId: systemId,
          reason: '책임 시스템 정정',
          evidenceMessageIds: [messageId],
          evidenceNote: null,
        },
      ],
      [
        processingCommand(ticketId, {
          requestId: makeId(),
          expectedTicketRevision: 6,
          processingType: 'INCIDENT',
          reason: '장애 재현',
          evidenceMessageIds: [],
          evidenceNote: '오류 재현 확인',
        }),
        '/tickets/' + ticketId + '/processing-type-corrections',
        {
          expectedTicketRevision: 6,
          processingType: 'INCIDENT',
          reason: '장애 재현',
          evidenceMessageIds: [],
          evidenceNote: '오류 재현 확인',
        },
      ],
      [
        generationRetryCommand(problemId, makeId()),
        '/case-generations/' + problemId + '/retries',
        {},
      ],
      [
        editCaseCommand(caseId, {
          requestId: makeId(),
          expectedCaseVersion: 2,
          title: '검토 사례',
          problemStatement: '문제 설명',
          action: '조치',
          verification: '검증',
          applicability: '적용 조건',
          limitations: ['한계'],
          accessScope: 'SYSTEM_STAFF',
          appliesToSystemVersion: null,
        }),
        '/cases/' + caseId + '/versions',
        {
          expectedCaseVersion: 2,
          title: '검토 사례',
          problemStatement: '문제 설명',
          action: '조치',
          verification: '검증',
          applicability: '적용 조건',
          limitations: ['한계'],
          accessScope: 'SYSTEM_STAFF',
          appliesToSystemVersion: null,
        },
      ],
      [
        approveCaseCommand(caseId, { requestId: makeId(), expectedCaseVersion: 3 }),
        '/cases/' + caseId + '/approvals',
        { expectedCaseVersion: 3 },
      ],
      [
        indexCommand(
          caseId,
          { requestId: makeId(), expectedCaseVersion: 3, store: 'VECTOR' },
          false,
        ),
        '/cases/' + caseId + '/index-checks',
        { expectedCaseVersion: 3, store: 'VECTOR' },
      ],
      [
        indexCommand(caseId, { requestId: makeId(), expectedCaseVersion: 3, store: 'NEO4J' }, true),
        '/cases/' + caseId + '/index-retries',
        { expectedCaseVersion: 3, store: 'NEO4J' },
      ],
    ];
    for (const [command, path, fields] of commands) {
      const received: {
        method?: string;
        path?: string;
        token?: string;
        key?: string | string[];
        body?: unknown;
      } = {};
      const resource = {
        type: command.expectedResource.type,
        id: command.expectedResource.id ?? crypto.randomUUID(),
      };
      handle = (req, res) => {
        Object.assign(received, {
          method: req.method,
          path: req.url,
          token: req.headers.authorization,
          key: req.headers['idempotency-key'],
        });
        let raw = '';
        req.setEncoding('utf8');
        req.on('data', (chunk: string) => {
          raw += chunk;
        });
        req.on('end', () => {
          received.body = JSON.parse(raw);
          json(res, 200, { ...receipt('SUCCEEDED', command.body.requestId), resource });
        });
      };
      expect(savedResource(await sendSystemCommand(command), resource.type)).toEqual(resource);
      expect(received).toEqual({
        method: 'POST',
        path: `/api/v2${path}`,
        token: 'Bearer synthetic-access',
        key: command.body.requestId,
        body: { requestId: command.body.requestId, ...fields },
      });
    }
  });
  it('성공 receipt의 대상이 다르면 저장 성공을 표시하지 않는다', async () => {
    const command = sendMessageCommand(crypto.randomUUID(), {
      requestId: crypto.randomUUID(),
      expectedInquiryVersion: 1,
      content: '문의',
      problemIds: [],
    });
    handle = (_req, res) => {
      json(res, 200, receipt('SUCCEEDED', command.body.requestId));
    };
    await expect(sendSystemCommand(command)).rejects.toMatchObject({
      body: { code: 'INVALID_RESPONSE' },
    });
  });
  it('live 조회는 실제 서버의 v2 조회·필터·커서를 사용하며 미구현 503을 그대로 표시한다', async () => {
    const inquiryId = crypto.randomUUID();
    const runId = crypto.randomUUID();
    const workItemId = crypto.randomUUID();
    const ticketId = crypto.randomUUID();
    const seen: string[] = [];
    handle = (req, res) => {
      seen.push(req.url ?? '');
      json(res, 503, error('SERVICE_UNAVAILABLE'));
    };
    const reads = [
      getInquirySystems(),
      getSystemInquiries(2),
      getSystemInquiry(inquiryId),
      getSystemMessages(inquiryId, 8),
      getSystemRun(runId),
      getCreationResult(workItemId),
      getSystemTickets('scope=ASSIGNED_SYSTEMS&state=WAITING&page=1&size=20'),
      getManagementTicket(ticketId),
      getSystemTicket(ticketId),
      getRelatedMessages(ticketId, 'opaque+/='),
      getPrimary(ticketId),
      getResolution(workItemId),
      getGeneration(workItemId),
      getCases('systemId=' + ticketId + '&state=DRAFT&page=1&size=20'),
      getCase(ticketId),
    ];
    const results = await Promise.allSettled(reads);
    results.forEach((result) => {
      expect(result).toMatchObject({
        status: 'rejected',
        reason: { status: 503, body: { code: 'SERVICE_UNAVAILABLE' } },
      });
    });
    expect(seen.sort()).toEqual(
      [
        '/api/v2/systems',
        '/api/v2/inquiries?page=2&size=20',
        `/api/v2/inquiries/${inquiryId}`,
        `/api/v2/inquiries/${inquiryId}/messages?size=30&beforeSequenceNo=8`,
        `/api/v2/runs/${runId}`,
        `/api/v2/work-items/${workItemId}`,
        '/api/v2/systems/' + ticketId + '/primary',
        '/api/v2/resolution-requests/' + workItemId,
        '/api/v2/case-generations/' + workItemId,
        '/api/v2/cases?systemId=' + ticketId + '&state=DRAFT&page=1&size=20',
        '/api/v2/cases/' + ticketId,
        '/api/v2/tickets?scope=ASSIGNED_SYSTEMS&state=WAITING&page=1&size=20',
        `/api/v2/management/tickets/${ticketId}`,
        `/api/v2/tickets/${ticketId}`,
        `/api/v2/tickets/${ticketId}/related-messages?size=30&cursor=opaque%2B%2F%3D`,
      ].sort(),
    );
  });
  it('F003-CONTRACT-08 POST 202는 접수 중이며 저장 성공으로 취급하지 않는다', async () => {
    const command = {
      path: '/api/v2/inquiries',
      body: { requestId: crypto.randomUUID(), content: '문의', reportedSystemId: null },
    };
    handle = (req, res) => {
      expect(req.url).toBe(command.path);
      expect(req.headers['idempotency-key']).toBe(command.body.requestId);
      json(res, 202, receipt('PENDING', command.body.requestId));
    };
    const result = await sendSystemCommand(command);
    expect(result.state).toBe('PENDING');
    expect(() => savedResource(result, 'INQUIRY')).toThrow();
    handle = (_req, res) => {
      json(res, 200, receipt('UNKNOWN', command.body.requestId));
    };
    await expect(sendSystemCommand(command)).rejects.toMatchObject({
      body: { code: 'INVALID_RESPONSE' },
    });
  });
  it('F010-CONTRACT-05 원 결과 410은 새 POST를 실행하지 않고 사용자에게 반환한다', async () => {
    const requests: string[] = [];
    handle = (req, res) => {
      requests.push(req.method ?? '');
      json(res, 410, error('OPERATION_RESULT_EXPIRED'));
    };
    await expect(
      sendSystemCommand(
        {
          path: '/api/v2/inquiries',
          body: { requestId: crypto.randomUUID(), content: '문의', reportedSystemId: null },
        },
        true,
      ),
    ).rejects.toMatchObject({ status: 410 });
    expect(requests).toEqual(['GET']);
  });
  it('F003-CONTRACT-04 원 결과 404 후 사용자가 요청한 재확인은 원 키·본문을 유지한다', async () => {
    const requestId = crypto.randomUUID();
    const body = { requestId, content: '원문 유지', reportedSystemId: null };
    let received = '';
    handle = (req, res) => {
      if (req.method === 'GET') {
        json(res, 404, error('NOT_FOUND'));
        return;
      }
      expect(req.headers['idempotency-key']).toBe(requestId);
      req.setEncoding('utf8');
      req.on('data', (chunk: string) => {
        received += chunk;
      });
      req.on('end', () => {
        json(res, 200, receipt('SUCCEEDED', requestId));
      });
    };
    expect((await sendSystemCommand({ path: '/api/v2/inquiries', body }, true)).state).toBe(
      'SUCCEEDED',
    );
    expect(JSON.parse(received)).toEqual(body);
  });
  it('F001-CONTRACT-01 v2 인증 경로의 쿠키 정책과 업무 Bearer를 구분한다', async () => {
    const seen: (string | undefined)[] = [];
    handle = (req, res) => {
      seen.push(req.headers.authorization);
      json(res, 200, {});
    };
    await apiRequest('/api/v2/auth/login', {
      method: 'POST',
      body: { loginId: 'synthetic', password: 'synthetic' },
    });
    await apiRequest('/api/v2/auth/me');
    expect(seen).toEqual([undefined, 'Bearer synthetic-access']);
  });
  it('F002-CONTRACT-01 신규 flat 상태 이벤트를 검증하고 같은 eventId는 한 번만 전달한다', async () => {
    const target = { type: 'SYSTEM_TICKETS' as const, id: crypto.randomUUID() };
    handle = (req, res) => {
      expect(req.url).toContain('/api/v2/realtime/events?');
      res.writeHead(200, { 'Content-Type': 'text/event-stream' });
      res.write(': connected\n\n');
      peers.push(res);
    };
    transport = new SseTransport(1000, 10);
    const received = vi.fn();
    transport.on('state.changed', received);
    transport.connect('synthetic-access');
    const off = transport.subscribe(target);
    await vi.waitFor(() => {
      expect(peers).toHaveLength(1);
    });
    const eventId = crypto.randomUUID();
    const event = {
      eventId,
      targetType: target.type,
      targetId: target.id,
      resourceType: 'TICKET',
      resourceId: crypto.randomUUID(),
      revision: 2,
    };
    const peer = peers[0];
    if (!peer) throw new Error('SSE 연결 없음');
    peer.write(
      `id: ${eventId}\nevent: state.changed\ndata: ${JSON.stringify(event)}\n\nid: ${eventId}\nevent: state.changed\ndata: ${JSON.stringify(event)}\n\n`,
    );
    await vi.waitFor(() => {
      expect(received).toHaveBeenCalledTimes(1);
    });
    expect(received).toHaveBeenCalledWith({ eventId, target, inquiryVersion: null });
    off();
  });
});
