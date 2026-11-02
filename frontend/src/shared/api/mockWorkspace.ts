import { contentError } from '@/shared/lib/content';
import { fixture } from './mockData';
import { mockError } from './mockError';
import type {
  ChatMessage,
  Department,
  CurrentUser,
  InquiryPage,
  InquiryResult,
  MessagePage,
  MessageHistoryPage,
  RunRetryRequest,
  Problem,
  RunSummary,
  SubscriptionTarget,
  TicketCard,
  TicketDetail,
} from './types';
import type { MutationResult } from './types';
import type { EventMap } from '@/shared/realtime/types';
import type { StoredCommand, StoredCommandName } from './mutations';

type Mode = 'simple' | 'partial' | 'timeout' | 'error' | 'outside' | 'cs';
type Record = {
  ownerId: string;
  title: string;
  createdAt: string;
  inquiry: InquiryResult;
  messages: ChatMessage[];
  mode: Mode;
};
type Publisher = <K extends keyof EventMap>(name: K, event: EventMap[K]) => void;

/** 공용 fixture를 초기 상태로 사용하는 메모리 서버 대역. 프론트 캐시와 분리한다. */
export class MockWorkspace {
  private records = new Map<string, Record>();
  private saved = new Map<string, { body: string; ack: MutationResult }>();
  private jobs = new Map<ReturnType<typeof setTimeout>, () => void>();
  private publisher: Publisher | null = null;
  private rejectNext = false;
  private retries = new Map<string, { body: string; run: RunSummary; inquiryId: string }>();
  private retrySources = new Map<string, string>();
  constructor() {
    this.reset();
  }
  setPublisher(publisher: Publisher) {
    this.publisher = publisher;
  }
  failNextCommand() {
    this.rejectNext = true;
  }
  reset() {
    for (const timer of this.jobs.keys()) clearTimeout(timer);
    this.jobs.clear();
    this.records.clear();
    this.saved.clear();
    this.retries.clear();
    this.retrySources.clear();
    this.rejectNext = false;
    const ownerId = fixture('user-0', 'CurrentUser').id;
    const inquiry = fixture('inquiry-result', 'InquiryResult');
    this.records.set(inquiry.id, {
      ownerId,
      title: 'VPN 접속 오류 외 2건',
      createdAt: fixture('message-user', 'ChatMessage').createdAt,
      inquiry,
      messages: fixture('messages', 'MessagePage').items,
      mode: 'partial',
    });
  }
  private owned(user: CurrentUser, id: string) {
    const record = this.records.get(id);
    if (!record) mockError(404, 'NOT_FOUND', '문의를 찾을 수 없어요.');
    if (record.ownerId !== user.id) mockError(403, 'ACCESS_DENIED', '이 문의를 볼 수 없어요.');
    return record;
  }
  readInquiry(user: CurrentUser, id: string) {
    return structuredClone(this.owned(user, id).inquiry);
  }
  readRun(user: CurrentUser, id: string): RunSummary {
    const record = [...this.records.values()].find((item) => item.inquiry.currentRun?.id === id);
    if (!record) mockError(404, 'NOT_FOUND', '분석 실행을 찾을 수 없어요.');
    this.owned(user, record.inquiry.id);
    const run = record.inquiry.currentRun;
    if (!run) mockError(404, 'NOT_FOUND', '분석 실행을 찾을 수 없어요.');
    return structuredClone(run);
  }
  listInquiries(user: CurrentUser, page: number, size: number): InquiryPage {
    const items = [...this.records.values()]
      .filter((item) => item.ownerId === user.id)
      .sort(
        (a, b) =>
          b.createdAt.localeCompare(a.createdAt) || b.inquiry.id.localeCompare(a.inquiry.id),
      )
      .map((item) => ({
        id: item.inquiry.id,
        title: item.title,
        status: item.inquiry.status,
        createdAt: item.createdAt,
      }));
    return {
      items: items.slice(page * size, (page + 1) * size),
      page,
      size,
      totalElements: items.length,
    };
  }
  messages(user: CurrentUser, id: string, after: number, size: number): MessagePage {
    const all = this.owned(user, id)
      .messages.filter((message) => message.sequenceNo > after)
      .sort((a, b) => a.sequenceNo - b.sequenceNo);
    const items = structuredClone(all.slice(0, size));
    return {
      items,
      nextAfterSequenceNo: all.length > size ? (items.at(-1)?.sequenceNo ?? null) : null,
    };
  }
  history(user: CurrentUser, id: string, before: number | null, size: number): MessageHistoryPage {
    const all = this.owned(user, id)
      .messages.filter((message) => before === null || message.sequenceNo < before)
      .sort((a, b) => a.sequenceNo - b.sequenceNo);
    const items = structuredClone(all.slice(-size));
    return {
      items,
      nextBeforeSequenceNo: all.length > size ? (items[0]?.sequenceNo ?? null) : null,
    };
  }
  retryRun(user: CurrentUser, id: string, request: RunRetryRequest): RunSummary {
    const record =
      [...this.records.values()].find((item) => item.inquiry.currentRun?.id === id) ??
      this.records.get(this.retrySources.get(id) ?? '');
    const key = `${user.id}:${id}:${request.requestId}`;
    const previous = this.retries.get(key);
    if (previous) {
      if (previous.body !== JSON.stringify(request))
        mockError(409, 'IDEMPOTENCY_KEY_REUSED', '요청 내용이 변경됐어요.');
      // 소유권은 응답 재사용 때도 확인한다.
      this.owned(user, previous.inquiryId);
      return structuredClone(previous.run);
    }
    if (!record) mockError(404, 'NOT_FOUND', '실행을 찾을 수 없어요. 최신 내용을 조회해 주세요.');
    this.owned(user, record.inquiry.id);
    if (record.inquiry.version !== request.expectedInquiryVersion)
      mockError(409, 'INQUIRY_VERSION_CONFLICT', '최신 내용을 확인해 주세요.');
    const current = record.inquiry.currentRun;
    if (!current) mockError(404, 'NOT_FOUND', '실행을 찾을 수 없어요.');
    if (current.status === 'RUNNING') {
      this.retries.set(key, {
        body: JSON.stringify(request),
        run: structuredClone(current),
        inquiryId: record.inquiry.id,
      });
      return structuredClone(current);
    }
    if (current.id !== id) mockError(409, 'RUN_NOT_RETRYABLE', '최신 실행 상태를 확인해 주세요.');
    if (!['TIMED_OUT', 'FAILED'].includes(current.status) || !current.error?.retryable)
      mockError(409, 'RUN_NOT_RETRYABLE', '이 실행은 다시 분석할 수 없어요.');
    const run = {
      id: crypto.randomUUID(),
      inquiryVersion: record.inquiry.version,
      status: 'RUNNING',
      error: null,
    };
    record.inquiry.currentRun = run;
    this.retrySources.set(id, record.inquiry.id);
    this.retries.set(key, {
      body: JSON.stringify(request),
      run: structuredClone(run),
      inquiryId: record.inquiry.id,
    });
    // 개발용 일시 장애는 재시도 후 정상화한다. 실제 단계 체크포인트 복구를 흉내 내지 않는다.
    record.mode = 'simple';
    this.scheduleAnalysis(record, run.id, run.inquiryVersion);
    this.changed(record);
    return structuredClone(run);
  }
  tickets(user: CurrentUser): TicketCard[] {
    return structuredClone(
      [...this.records.values()]
        .flatMap((item) => item.inquiry.tickets)
        .filter(
          (ticket) =>
            user.roles.includes('BIZ_ADMIN') ||
            (user.department.isRoutable && ticket.department.id === user.department.id),
        ),
    ).sort((a, b) => b.createdAt.localeCompare(a.createdAt) || b.id.localeCompare(a.id));
  }
  ticket(user: CurrentUser, id: string): TicketDetail {
    const all = [...this.records.values()].flatMap((item) => item.inquiry.tickets);
    const ticket = all.find((item) => item.id === id);
    if (!ticket) mockError(404, 'NOT_FOUND', '티켓을 찾을 수 없어요.');
    if (!this.tickets(user).some((item) => item.id === id))
      mockError(403, 'ACCESS_DENIED', '이 티켓을 볼 수 없어요.');
    const template = fixture('ticket-detail', 'TicketDetail');
    return {
      ...template,
      ...structuredClone(ticket),
      description: ticket.id === template.id ? template.description : ticket.title,
      diagnosisSummary: ticket.id === template.id ? template.diagnosisSummary : null,
      assignmentHistory: [
        { department: ticket.department, assignedAt: ticket.createdAt, endedAt: null },
      ],
    };
  }
  /** 테스트에서 서버 측 재이관 후 권한 변화를 재현한다. 사용자 조작 API가 아니다. */
  reassignForTest(id: string, department: Department) {
    for (const record of this.records.values()) {
      const ticket = record.inquiry.tickets.find((item) => item.id === id);
      if (ticket) {
        ticket.department = structuredClone(department);
        return;
      }
    }
    mockError(404, 'NOT_FOUND', '티켓을 찾을 수 없어요.');
  }
  authorize(user: CurrentUser, target: SubscriptionTarget) {
    if (target.type === 'INQUIRY') {
      this.owned(user, target.id);
      return;
    }
    if (target.type === 'TICKET') {
      this.ticket(user, target.id);
      return;
    }
    if (!(
      user.roles.includes('BIZ_ADMIN') ||
      (user.department.isRoutable && user.department.id === target.id)
    ))
      mockError(403, 'ACCESS_DENIED', '이 부서의 티켓을 볼 수 없어요.');
  }
  command(user: CurrentUser, name: StoredCommandName, command: StoredCommand): MutationResult {
    const scope =
      name === 'inquiry.create'
        ? '/api/inquiries'
        : 'inquiryId' in command
          ? `/api/inquiries/${command.inquiryId}/messages${'messageId' in command ? `/${command.messageId}/revisions` : ''}`
          : name;
    const key = `${user.id}:POST:${scope}:${command.requestId}`;
    const body = JSON.stringify(command, Object.keys(command).sort());
    const previous = this.saved.get(key);
    if (previous) {
      if (previous.body !== body)
        mockError(409, 'IDEMPOTENCY_KEY_REUSED', '내용이 바뀐 요청은 새로 보내 주세요.');
      return structuredClone(previous.ack);
    }
    if (this.rejectNext) {
      this.rejectNext = false;
      mockError(503, 'SERVICE_UNAVAILABLE', '메시지를 저장하지 못했어요. 다시 시도해 주세요.');
    }
    if (contentError(command.content))
      mockError(400, 'INVALID_INPUT', '공백을 제외한 내용을 4,000자 이내로 입력해 주세요.');
    const now = new Date().toISOString();
    let record: Record;
    let message: ChatMessage;
    let supplement = false;
    if (name === 'inquiry.create') {
      const id = crypto.randomUUID();
      const mode: Mode = command.content.includes('시간 초과')
        ? 'timeout'
        : command.content.includes('모델 오류')
          ? 'error'
          : command.content.includes('날씨')
            ? 'outside'
            : command.content.includes('CS 검토')
              ? 'cs'
              : command.content.includes('권한')
                ? 'partial'
                : 'simple';
      record = {
        ownerId: user.id,
        title: Array.from(command.content).slice(0, 40).join(''),
        createdAt: now,
        inquiry: {
          id,
          version: 1,
          status: 'IN_PROGRESS',
          problems: [],
          tickets: [],
          creationFailures: [],
          currentRun: null,
        },
        messages: [],
        mode,
      };
      message = {
        id: crypto.randomUUID(),
        inquiryId: id,
        revision: 1,
        sequenceNo: 1,
        sender: 'USER',
        content: command.content,
        createdAt: now,
        revisions: [],
      };
      record.messages.push(message);
      this.records.set(id, record);
    } else {
      if (!('inquiryId' in command)) mockError(400, 'INVALID_INPUT', '문의 정보가 필요해요.');
      record = this.owned(user, command.inquiryId);
      if (command.expectedInquiryVersion !== record.inquiry.version)
        mockError(
          409,
          'INQUIRY_VERSION_CONFLICT',
          '최신 대화를 다시 불러왔어요. 내용을 확인한 뒤 보내 주세요.',
        );
      supplement =
        record.inquiry.problems.length > 0 &&
        record.inquiry.problems.every((problem) =>
          ['TICKET_CREATED', 'OUT_OF_SCOPE'].includes(problem.status),
        );
      if (name === 'message.revise') {
        if (!('messageId' in command)) mockError(400, 'INVALID_INPUT', '정정할 메시지가 필요해요.');
        const existing = record.messages.find((item) => item.id === command.messageId);
        if (existing?.sender !== 'USER')
          mockError(403, 'ACCESS_DENIED', '본인이 보낸 메시지만 고칠 수 있어요.');
        existing.revisions.push({
          revisionNo: existing.revision,
          content: existing.content,
          createdAt: now,
        });
        existing.revision += 1;
        existing.content = command.content;
        message = existing;
      } else {
        message = {
          id: crypto.randomUUID(),
          inquiryId: record.inquiry.id,
          revision: 1,
          sequenceNo: (record.messages.at(-1)?.sequenceNo ?? 0) + 1,
          sender: 'USER',
          content: command.content,
          createdAt: now,
          revisions: [],
        };
        record.messages.push(message);
      }
      record.inquiry.version += 1;
    }
    const version = record.inquiry.version;
    const runId = supplement ? null : crypto.randomUUID();
    if (runId) {
      record.inquiry.currentRun = {
        id: runId,
        inquiryVersion: version,
        status: 'RUNNING',
        error: null,
      };
      record.inquiry.status = 'IN_PROGRESS';
      this.scheduleAnalysis(record, runId, version);
    }
    const ack: MutationResult = {
      requestId: command.requestId,
      inquiryId: record.inquiry.id,
      inquiryVersion: version,
      messageId: message.id,
      revision: message.revision,
      runId,
      effect: supplement ? 'SUPPLEMENT_SAVED' : 'ANALYSIS_STARTED',
    };
    this.saved.set(key, { body, ack: structuredClone(ack) });
    this.publisher?.('message.stored', {
      eventId: crypto.randomUUID(),
      inquiryId: record.inquiry.id,
      inquiryVersion: version,
      messageId: message.id,
      requestId: command.requestId,
    });
    return ack;
  }
  private schedule(delay: number, action: () => void) {
    const timer = setTimeout(() => {
      this.jobs.delete(timer);
      action();
    }, delay);
    this.jobs.set(timer, action);
  }
  flushPending() {
    for (const [timer, job] of [...this.jobs]) {
      clearTimeout(timer);
      this.jobs.delete(timer);
      job();
    }
  }
  private scheduleAnalysis(record: Record, runId: string, version: number) {
    const valid = () =>
      record.inquiry.version === version && record.inquiry.currentRun?.id === runId;
    this.schedule(120, () => {
      if (valid())
        this.publisher?.('agent.progress', {
          eventId: crypto.randomUUID(),
          inquiryId: record.inquiry.id,
          inquiryVersion: version,
          runId,
          payload: {
            stepNo: 1,
            stepType: 'INTAKE',
            displaySummary: '문의 내용을 확인하고 있어요.',
            status: 'RUNNING',
          },
        });
    });
    const answerId = crypto.randomUUID();
    const answer =
      record.mode === 'outside'
        ? '이 질문은 사내 IT 지원 범위 밖이에요.'
        : record.mode === 'simple' && record.inquiry.problems.length === 0
          ? fixture('message-ai', 'ChatMessage').content
          : '문제별 처리 현황을 정리했어요. 추가 정보는 아래에 남겨 주세요.';
    const chunks = [answer.slice(0, 8), answer.slice(8)];
    chunks.forEach((text, index) => {
      this.schedule(240 + index * 80, () => {
        if (valid())
          this.publisher?.('answer.delta', {
            eventId: crypto.randomUUID(),
            inquiryId: record.inquiry.id,
            inquiryVersion: version,
            runId,
            payload: { messageId: answerId, seq: index + 1, text },
          });
      });
    });
    this.schedule(480, () => {
      if (!valid()) return;
      const run = record.inquiry.currentRun;
      if (!run) return;
      if (record.mode === 'timeout' || record.mode === 'error') {
        run.status = record.mode === 'timeout' ? 'TIMED_OUT' : 'FAILED';
        run.error = {
          code: record.mode === 'timeout' ? 'DEADLINE_EXCEEDED' : 'MODEL_UNAVAILABLE',
          message: '분석을 끝내지 못했어요. 이미 저장된 결과는 유지돼요.',
          requestId: runId,
          retryable: true,
        };
        this.changed(record);
        return;
      }
      if (record.mode === 'outside') {
        record.inquiry.problems = [
          {
            id: crypto.randomUUID(),
            summary: record.title,
            category: null,
            status: 'OUT_OF_SCOPE',
            questionCount: 0,
          },
        ];
      } else if (record.mode === 'simple' && record.inquiry.problems.length === 0) {
        record.inquiry.problems = [
          {
            id: crypto.randomUUID(),
            summary: record.title,
            category: 'NETWORK_ACCESS',
            status: 'WAITING_USER',
            questionCount: 1,
          },
        ];
        record.inquiry.status = 'WAITING_USER';
      } else if (record.mode === 'partial') {
        const template = fixture('inquiry-result', 'InquiryResult');
        if (record.inquiry.problems.length === 0) {
          record.inquiry.problems = template.problems.map((problem) => ({
            ...problem,
            id: crypto.randomUUID(),
          }));
          const main = record.inquiry.problems[0];
          const failed = record.inquiry.problems[1];
          if (main && failed) {
            record.inquiry.tickets = template.tickets.map((ticket) => ({
              ...ticket,
              id: crypto.randomUUID(),
              inquiryId: record.inquiry.id,
              problemId: main.id,
              workItemId: crypto.randomUUID(),
              createdAt: new Date().toISOString(),
            }));
            record.inquiry.creationFailures = template.creationFailures.map((failure) => ({
              ...failure,
              workItemId: crypto.randomUUID(),
              problemId: failed.id,
            }));
          }
        } else {
          const waiting = record.inquiry.problems.find(
            (problem) => problem.status === 'WAITING_USER',
          );
          if (waiting) {
            waiting.questionCount = Math.min(3, waiting.questionCount + 1);
            if (waiting.questionCount === 3) this.addTicket(record, waiting, true);
          }
        }
        record.inquiry.status = 'WAITING_USER';
      } else {
        const problem =
          record.inquiry.problems[0] ??
          ({
            id: crypto.randomUUID(),
            summary: record.title,
            category: 'OTHER',
            status: 'ANALYZING',
            questionCount: record.mode === 'cs' ? 3 : 1,
          } satisfies Problem);
        if (record.inquiry.problems.length === 0) record.inquiry.problems.push(problem);
        this.addTicket(record, problem, record.mode === 'cs');
      }
      run.status = 'SUCCEEDED';
      const final = {
        id: answerId,
        inquiryId: record.inquiry.id,
        revision: 1,
        sequenceNo: (record.messages.at(-1)?.sequenceNo ?? 0) + 1,
        sender: 'AI',
        content: answer,
        createdAt: new Date().toISOString(),
        revisions: [],
      } satisfies ChatMessage;
      record.messages.push(final);
      this.publisher?.('message.stored', {
        eventId: crypto.randomUUID(),
        inquiryId: record.inquiry.id,
        inquiryVersion: version,
        messageId: answerId,
        requestId: null,
      });
      this.changed(record);
    });
  }
  private addTicket(record: Record, problem: Problem, cs: boolean) {
    if (!record.inquiry.tickets.some((ticket) => ticket.problemId === problem.id)) {
      const template = cs
        ? fixture('ticket-cs', 'TicketCard')
        : fixture('ticket-network', 'TicketCard');
      record.inquiry.tickets.push({
        ...template,
        id: crypto.randomUUID(),
        inquiryId: record.inquiry.id,
        problemId: problem.id,
        workItemId: crypto.randomUUID(),
        title: problem.summary,
        createdAt: new Date().toISOString(),
      });
    }
    problem.status = 'TICKET_CREATED';
  }
  private changed(record: Record) {
    this.publisher?.('state.changed', {
      eventId: crypto.randomUUID(),
      target: { type: 'INQUIRY', id: record.inquiry.id },
      inquiryVersion: record.inquiry.version,
    });
    for (const ticket of record.inquiry.tickets) {
      this.publisher?.('state.changed', {
        eventId: crypto.randomUUID(),
        target: { type: 'DEPARTMENT_TICKETS', id: ticket.department.id },
        inquiryVersion: null,
      });
      this.publisher?.('state.changed', {
        eventId: crypto.randomUUID(),
        target: { type: 'TICKET', id: ticket.id },
        inquiryVersion: null,
      });
    }
  }
}

export const mockWorkspace = new MockWorkspace();
