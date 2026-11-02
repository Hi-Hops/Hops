import { readMockAuth } from '@/shared/config/mockAuth';
import { contentError } from '@/shared/lib/content';
import { isRecord, isUuid } from './wire';
import { mockError } from './mockError';
import { systems, users } from './systemMockData';
import { SystemMockWorkflow } from './systemMockWorkflow';
import { mockExamples } from './systemMockExamples';
import type * as D from './types/hopsSystem1';
import type { SubscriptionTarget } from './types';

type Workspace = { ownerId: string; result: D.InquiryResult; messages: D.ChatMessage[] };
type Journal = { digest: string; userId: string; createdAt: number; receipt: D.CommandReceipt };
const now = () => new Date().toISOString();
const clone = <T>(value: T): T => structuredClone(value);
export class SystemMockBackend {
  private sessions = new Map<string, { userId: string; expiresAt: number }>();
  private refresh: { userId: string; expiresAt: number } | null = null;
  private inquiries = new Map<string, Workspace>();
  private details = new Map<string, D.TicketDetail>();
  private journal = new Map<string, Journal>();
  private listeners = new Set<() => void>();
  private lost = false;
  private delay = 180;
  private timers = new Set<ReturnType<typeof setTimeout>>();
  private people = clone(users);
  private failure: number | null = null;
  private workflow = new SystemMockWorkflow({
    people: () => this.people,
    ticket: (user, id, admin) => this.ticket(user, id, admin),
    findTicket: (id) => this.details.get(id),
    inquiryOwner: (id) => this.inquiries.get(id)?.ownerId,
    sync: (ticket) => {
      this.syncTicket(ticket);
    },
    guideResponse: (request) => {
      const workspace = this.inquiries.get(request.inquiryId);
      const problem = workspace?.result.problems.find((p) => p.id === request.problemId);
      if (workspace && problem) {
        problem.state = request.state === 'RESOLVED' ? 'RESOLVED' : 'ANALYZING';
        if (request.state === 'UNRESOLVED') this.storeTicket(workspace, problem);
        this.aggregate(workspace);
      }
    },
    evidenceAllowed: (ticket, ids) =>
      ids.every((id) =>
        this.inquiries
          .get(ticket.inquiryId)
          ?.messages.some(
            (m) => m.id === id && m.problemIds.length === 1 && m.problemIds[0] === ticket.problemId,
          ),
      ),
    changed: () => {
      this.changed();
    },
  });

  constructor() {
    this.reset();
  }
  reset() {
    this.workflow.reset();
    for (const timer of this.timers) clearTimeout(timer);
    this.timers.clear();
    this.sessions.clear();
    this.refresh = null;
    this.inquiries.clear();
    this.details.clear();
    this.journal.clear();
    this.people = clone(users);
    this.lost = false;
    this.failure = null;
    const workspace = this.createWorkspace(
      this.people[0],
      '전자결재에서 문서를 열 수 없어요',
      systems[0].id,
    );
    if (workspace.result.problems[0]) this.storeTicket(workspace, workspace.result.problems[0]);
    const fileWorkspace = this.createWorkspace(
      this.people[0],
      '파일 공유의 접근 권한을 확인해 주세요',
      systems[2].id,
    );
    if (fileWorkspace.result.problems[0])
      this.storeTicket(fileWorkspace, fileWorkspace.result.problems[0]);
    if (
      import.meta.env.VITE_DATA_MODE?.trim() === 'mock' &&
      import.meta.env.VITE_MOCK_EXAMPLES === 'true'
    )
      this.seedExamples();
  }
  private seedExamples() {
    const requester = this.people[0];
    for (const example of mockExamples) {
      const workspace = this.createWorkspace(
        requester,
        example.title,
        systems[example.systemIndex].id,
      );
      const problem = workspace.result.problems[0];
      if (!problem) continue;
      problem.questionCount = 1;
      this.message(
        workspace,
        '시연용으로 구성한 합성 문의예요. 아래 티켓과 처리 상태를 확인해 주세요.',
        'AI',
        null,
      );
      this.storeTicket(workspace, problem);
      const ticket = problem.ticket && this.details.get(problem.ticket.id);
      if (!ticket) continue;
      ticket.standardRequest.details.taskOrScreen = '시연용 업무 화면';
      ticket.standardRequest.details.actualBehavior =
        '가상 입력에서 접근 또는 문서 처리 오류가 발생해요.';
      ticket.standardRequest.details.expectedBehavior = '동일 화면에서 업무를 정상적으로 처리해요.';
      this.workflow.seedExample(ticket, requester, example.stage);
      this.syncTicket(ticket);
    }
    const waiting = this.createWorkspace(requester, '[예시] 시스템을 알 수 없는 접속 문제', null);
    const waitingProblem = waiting.result.problems[0];
    if (waitingProblem) {
      waitingProblem.state = 'WAITING_SYSTEM';
      waitingProblem.questionCount = 5;
      this.message(
        waiting,
        '질문 한도에 도달했어요. 보고 시스템을 선택하면 같은 문제의 접수를 이어가요.',
        'AI',
        null,
      );
      this.aggregate(waiting);
    }
    const question = this.createWorkspace(
      requester,
      '[예시] 추가 정보가 필요한 문서 오류',
      systems[0].id,
    );
    const questionProblem = question.result.problems[0];
    if (questionProblem) {
      questionProblem.state = 'WAITING_USER';
      questionProblem.questionCount = 1;
      this.message(
        question,
        '어느 화면에서 언제 발생했고, 기대한 동작과 실제 현상이 무엇인지 알려 주세요.',
        'AI',
        null,
      );
      this.aggregate(question);
    }
    const guide = this.createWorkspace(requester, '[예시] 파일 공유 사용법 안내', systems[2].id);
    const guideProblem = guide.result.problems[0];
    if (guideProblem) {
      guideProblem.intent = 'HOW_TO';
      guideProblem.processingType = 'KNOWLEDGE_QA';
      const message = this.message(
        guide,
        '가상 파일 공유의 안내예요. 파일 선택 후 공유 대상과 열람 범위를 확인해 주세요.',
        'AI',
        null,
      );
      this.workflow.guide(guideProblem, message.id);
      this.aggregate(guide);
    }
    const mixed = this.createWorkspace(
      requester,
      '[예시] 문서 오류와 기능 요청을 동시에 문의',
      systems[0].id,
    );
    const first = mixed.result.problems[0];
    const second = mixed.result.problems[1];
    if (first) {
      first.summary = '[예시] 전자결재 문서 열기 오류';
      first.intent = 'OBSERVED_SYMPTOM';
      first.processingType = 'INVESTIGATION';
      this.storeTicket(mixed, first);
    }
    if (second) {
      second.state = 'TICKET_PENDING';
      second.creationResult = {
        workItemId: second.id,
        problemId: second.id,
        inquiryVersion: 1,
        outcome: 'UNKNOWN',
        ticketId: null,
        error: null,
        updatedAt: now(),
      };
    }
    this.message(
      mixed,
      '첫 문제의 티켓은 저장됐고 두 번째 문제는 저장 결과를 확인 중인 가상 예시예요.',
      'AI',
      null,
    );
    this.aggregate(mixed);
  }
  loseNextResponse() {
    this.lost = true;
  }
  setNextGenerationOutcome(value: 'FAILED' | 'NO_REUSABLE_CASE' | 'SUCCEEDED') {
    this.workflow.setGenerationOutcome(value);
  }
  failNextIndex(store: D.IndexStore) {
    this.workflow.failIndex(store);
  }
  expire(token: string) {
    const session = this.sessions.get(token);
    if (session) session.expiresAt = 0;
  }
  expireRefresh() {
    if (this.refresh) this.refresh.expiresAt = 0;
  }
  failNext(status = 503) {
    this.failure = status;
  }
  takeLostResponse() {
    const lost = this.lost;
    this.lost = false;
    return lost;
  }
  onChange(listener: () => void) {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }
  private changed() {
    for (const listener of this.listeners) listener();
  }
  revokeSystem(userId: string, systemId: string) {
    const user = this.people.find((p) => p.id === userId);
    if (user) user.primarySystemIds = user.primarySystemIds.filter((id) => id !== systemId);
    this.changed();
  }
  currentUser(token: string | null): D.CurrentUser {
    const session = token ? this.sessions.get(token) : null;
    if (!session || session.expiresAt <= Date.now())
      mockError(401, 'ACCESS_TOKEN_EXPIRED', '로그인이 만료됐어요.');
    const user = this.people.find((p) => p.id === session.userId);
    if (!user) mockError(401, 'UNAUTHENTICATED', '로그인이 필요해요.');
    return clone(user);
  }
  private workspace(user: D.CurrentUser, id: string) {
    const item = this.inquiries.get(id);
    if (!item) mockError(404, 'NOT_FOUND', '문의를 찾을 수 없어요.');
    if (item.ownerId !== user.id) mockError(403, 'ACCESS_DENIED', '본인 문의만 조회할 수 있어요.');
    return item;
  }
  private ticket(user: D.CurrentUser, id: string, admin = false) {
    const item = this.details.get(id);
    if (!item) mockError(404, 'NOT_FOUND', '티켓을 찾을 수 없어요.');
    if (
      admin
        ? !user.roles.includes('BIZ_ADMIN')
        : !user.primarySystemIds.includes(item.responsibleSystem.id)
    )
      mockError(403, 'ACCESS_DENIED', '이 시스템 업무에 접근할 권한이 없어요.');
    return item;
  }
  authorize(token: string, target: SubscriptionTarget) {
    const user = this.currentUser(token);
    if (target.type === 'INQUIRY') this.workspace(user, target.id);
    else if (target.type === 'TICKET') this.ticket(user, target.id);
    else if (target.type === 'CASE') this.workflow.authorizeCase(user, target.id);
    else {
      if (!user.primarySystemIds.includes(target.id) && !user.roles.includes('BIZ_ADMIN'))
        mockError(403, 'ACCESS_DENIED', '시스템 담당 권한이 없어요.');
    }
  }
  private message(
    workspace: Workspace,
    content: string,
    sender: 'USER' | 'AI',
    requestId: string | null,
    id: string = crypto.randomUUID(),
  ) {
    const message: D.ChatMessage = {
      id,
      inquiryId: workspace.result.id,
      requestId,
      revision: 1,
      sequenceNo: workspace.messages.length + 1,
      sender,
      content,
      problemIds: workspace.result.problems.map((p) => p.id),
      revisions: [],
      createdAt: now(),
    };
    workspace.messages.push(message);
    return message;
  }
  private createWorkspace(
    user: D.CurrentUser,
    content: string,
    systemId: string | null,
    requestId: string | null = null,
  ) {
    const id = crypto.randomUUID();
    const workspace: Workspace = {
      ownerId: user.id,
      messages: [],
      result: {
        id,
        inquiryNumber: `Q-${String(this.inquiries.size + 1).padStart(4, '0')}`,
        inquiryVersion: 1,
        status: 'IN_PROGRESS',
        problems: [],
        currentRun: null,
        pendingTargetMessageIds: [],
        resultRevision: 1,
        createdAt: now(),
        updatedAt: now(),
      },
    };
    const parts = content.includes('동시에') ? [content, '일괄 다운로드 기능 추가'] : [content];
    workspace.result.problems = parts.map((summary) => ({
      id: crypto.randomUUID(),
      inquiryId: id,
      summary,
      intent: summary.includes('기능') ? 'FEATURE_REQUEST' : 'OBSERVED_SYMPTOM',
      processingType: summary.includes('기능') ? 'CHANGE_REQUEST' : 'INVESTIGATION',
      reportedSystemId: systemId,
      reportedFeatureId: null,
      state: 'ANALYZING',
      questionCount: 0,
      guide: null,
      creationResult: null,
      ticket: null,
      activeResolutionRequest: null,
    }));
    this.inquiries.set(id, workspace);
    this.message(workspace, content, 'USER', requestId);
    return workspace;
  }
  private storeTicket(workspace: Workspace, problem: D.Problem) {
    if (problem.ticket) return;
    const reported = systems.find((s) => s.id === problem.reportedSystemId);
    if (!reported) return;
    const responsible = problem.summary.includes('로그인') ? systems[1] : reported;
    const owner = this.people.find((p) => p.primarySystemIds.includes(responsible.id));
    if (!owner) mockError(503, 'ASSIGNMENT_UNAVAILABLE', '현재 주담당자를 확인하지 못했어요.');
    const primary: D.UserSummary = { id: owner.id, name: owner.name, department: owner.department };
    const ticket: D.TicketSummary = {
      id: crypto.randomUUID(),
      inquiryId: workspace.result.id,
      problemId: problem.id,
      workItemId: problem.id,
      ticketNumber: `TK-${String(this.details.size + 1).padStart(4, '0')}`,
      title: problem.summary,
      processingType: problem.processingType,
      reportedSystem: reported,
      responsibleSystem: responsible,
      assignee: primary,
      state: 'WAITING',
      ticketRevision: 1,
      createdAt: now(),
      updatedAt: now(),
    };
    const standardRequest: D.StandardRequest = {
      id: crypto.randomUUID(),
      problemId: problem.id,
      inquiryVersion: workspace.result.inquiryVersion,
      summary: problem.summary,
      confirmedFacts: [
        {
          text: problem.summary,
          sourceMessageIds: workspace.messages[0] ? [workspace.messages[0].id] : [],
          knowledgeEvidenceIds: [],
        },
      ],
      assumptions: [],
      unknowns: ['원인과 적용 시스템 버전 미확인'],
      responsibleSystemCandidateId: responsible.id,
      responsibilityEvidenceIds: [],
      details: {
        taskOrScreen: null,
        requestedAccess: null,
        businessPurpose: null,
        expectedBehavior: null,
        actualBehavior: null,
        occurredAt: null,
        currentWorkflow: null,
        desiredChange: null,
        acceptanceCriteria: [],
      },
      knowledge: [],
    };
    this.details.set(ticket.id, {
      ...ticket,
      currentPrimary: primary,
      standardRequest,
      assignmentHistory: [
        {
          id: crypto.randomUUID(),
          previousSystemId: null,
          previousAssigneeId: null,
          systemId: responsible.id,
          assignee: primary,
          reason: '시스템 주담당자 자동 배정',
          evidenceMessageIds: [],
          evidenceNote: null,
          actorId: null,
          createdAt: now(),
        },
      ],
      workRecords: [],
      activeResolutionRequest: null,
      caseGeneration: null,
    });
    problem.ticket = ticket;
    problem.state = 'IN_SERVICE';
    problem.creationResult = {
      workItemId: problem.id,
      problemId: problem.id,
      inquiryVersion: workspace.result.inquiryVersion,
      outcome: 'STORED',
      ticketId: ticket.id,
      error: null,
      updatedAt: now(),
    };
    workspace.result.resultRevision++;
  }
  private analyze(workspace: Workspace) {
    const run: D.RunSummary = {
      id: crypto.randomUUID(),
      inquiryId: workspace.result.id,
      inquiryVersion: workspace.result.inquiryVersion,
      answerMessageId: crypto.randomUUID(),
      status: 'QUEUED',
      error: null,
    };
    workspace.result.currentRun = run;
    const timer = setTimeout(() => {
      this.timers.delete(timer);
      if (workspace.result.currentRun?.id !== run.id) return;
      const latest =
        [...workspace.messages].reverse().find((m) => m.sender === 'USER')?.content ?? '';
      let answer = 'Mock에서 확인한 접수 결과예요. 실제 업무 처리 결과가 아니에요.';
      if (/시간 초과|모델 오류/u.test(latest)) {
        run.status = latest.includes('시간 초과') ? 'TIMED_OUT' : 'FAILED';
        run.error = {
          code: 'MODEL_UNAVAILABLE',
          message: 'Mock 분석을 마치지 못했어요.',
          requestId: workspace.messages[0]?.requestId ?? crypto.randomUUID(),
          retryable: true,
          fieldErrors: [],
        };
      } else {
        for (const problem of workspace.result.problems) {
          if (problem.ticket) continue;
          if (/사용법|안내만/u.test(latest) && problem.reportedSystemId) {
            this.workflow.guide(problem, run.answerMessageId);
            answer = 'Mock 직원용 안내예요. 안내를 확인하고 문제별 해결 여부를 알려 주세요.';
            continue;
          }
          if (/날씨|범위 밖/u.test(latest)) {
            problem.state = 'OUT_OF_SCOPE';
            answer = '지원 범위 밖 문의예요. 전자결재·공통 로그인·파일 공유를 지원해요.';
          } else if (
            problem.questionCount === 0 ||
            (!problem.reportedSystemId && problem.questionCount < 5)
          ) {
            problem.questionCount++;
            problem.state = 'WAITING_USER';
            answer = '어떤 시스템에서 언제 발생했는지, 기대한 동작과 실제 현상을 알려 주세요.';
          } else if (!problem.reportedSystemId) {
            problem.state = 'WAITING_SYSTEM';
            answer = '질문 한도에 도달했어요. 아래에서 지원 시스템을 선택해 주세요.';
          } else if (latest.includes('저장 미확인') || latest.includes('생성 실패')) {
            problem.state = 'TICKET_PENDING';
            problem.creationResult = {
              workItemId: problem.id,
              problemId: problem.id,
              inquiryVersion: workspace.result.inquiryVersion,
              outcome: latest.includes('저장 미확인') ? 'UNKNOWN' : 'NOT_CREATED',
              ticketId: null,
              error: null,
              updatedAt: now(),
            };
            workspace.result.resultRevision++;
          } else this.storeTicket(workspace, problem);
        }
        this.message(workspace, answer, 'AI', null, run.answerMessageId);
        run.status = 'SUCCEEDED';
      }
      this.aggregate(workspace);
      this.changed();
    }, this.delay);
    this.timers.add(timer);
    this.changed();
    return run;
  }
  request(
    path: string,
    method: string,
    body: unknown,
    token: string | null,
    key?: string,
  ): unknown {
    if (!path.startsWith('/api/v2/')) mockError(404, 'NOT_FOUND', '지원하지 않는 API 경로예요.');
    if (this.failure !== null) {
      const status = this.failure;
      this.failure = null;
      mockError(
        status,
        'SERVICE_UNAVAILABLE',
        '서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.',
      );
    }
    const url = new URL(path, 'http://mock.invalid');
    const route = url.pathname.replace(/^\/api\/v2/u, '');
    const parts = route.split('/').filter(Boolean);
    if (route === '/auth/login' || route === '/auth/refresh') {
      let userId: string;
      if (route.endsWith('login')) {
        const config = readMockAuth();
        if (!config || !isRecord(body))
          mockError(503, 'MOCK_AUTH_NOT_CONFIGURED', 'Mock 로그인 환경 설정을 확인해 주세요.');
        const account = config.accounts.find((a) => a.loginId === body.loginId);
        if (!account || body.password !== config.password)
          mockError(401, 'UNAUTHENTICATED', '아이디 또는 비밀번호를 확인해 주세요.');
        const index = (
          { PREVIEW: 1, REQUESTER: 0, SYSTEM_STAFF: 1, FILE_STAFF: 3, ADMIN: 2 } as const
        )[account.role];
        const user = clone(this.people[index]);
        if (account.role === 'PREVIEW') user.roles = ['BIZ_ADMIN'];
        if (account.role === 'PREVIEW') this.people[index] = user;
        userId = user.id;
        this.refresh = { userId, expiresAt: Date.now() + 604800000 };
      } else {
        if (!this.refresh || this.refresh.expiresAt <= Date.now())
          mockError(401, 'REFRESH_TOKEN_EXPIRED', '인증이 만료됐어요.');
        userId = this.refresh.userId;
      }
      const accessToken = `system-mock:${crypto.randomUUID()}`;
      const expiresAt = Date.now() + 900000;
      this.sessions.set(accessToken, { userId, expiresAt });
      return {
        accessToken,
        expiresAt: new Date(expiresAt).toISOString(),
        user: this.currentUser(accessToken),
      };
    }
    if (route === '/auth/logout') {
      if (token) this.sessions.delete(token);
      this.refresh = null;
      return undefined;
    }
    const user = this.currentUser(token);
    if (route === '/auth/me') return user;
    if (route === '/systems') return clone(systems);
    if (parts[0] === 'operations') {
      const entry = [...this.journal.values()].find(
        (j) =>
          j.userId === user.id &&
          (parts[1] === 'by-request'
            ? j.receipt.requestId === parts[2]
            : j.receipt.operationId === parts[1]),
      );
      if (!entry) mockError(404, 'NOT_FOUND', '요청 결과를 확인하지 못했어요.');
      if (Date.now() - entry.createdAt >= 86400000)
        mockError(410, 'OPERATION_RESULT_EXPIRED', '요청 결과 보존 기간이 지났어요.');
      this.workflow.authorizeResource(user, entry.receipt.resource);
      return clone(entry.receipt);
    }
    if (method === 'POST') {
      if (!isRecord(body) || !isUuid(body.requestId) || key !== body.requestId)
        mockError(400, 'INVALID_INPUT', '요청 키를 확인해 주세요.');
      const journalKey = `${user.id}:${key}`;
      const digest = JSON.stringify({ path, method, body });
      const previous = this.journal.get(journalKey);
      if (previous) {
        if (previous.digest !== digest)
          mockError(409, 'IDEMPOTENCY_KEY_REUSED', '다른 요청에 사용한 키예요.');
        if (Date.now() - previous.createdAt >= 86400000)
          mockError(410, 'OPERATION_RESULT_EXPIRED', '원 업무 상태를 확인해 주세요.');
        this.workflow.authorizeResource(user, previous.receipt.resource);
        return clone(previous.receipt);
      }
      let resource: D.ResourceRef;
      const workflowResource = [
        'tickets',
        'resolution-requests',
        'cases',
        'case-generations',
      ].includes(parts[0] ?? '')
        ? this.workflow.post(user, parts, body)
        : null;
      if (workflowResource) resource = workflowResource;
      else if (route === '/inquiries') {
        if (typeof body.content !== 'string' || contentError(body.content))
          mockError(400, 'INVALID_INPUT', '문의 내용을 확인해 주세요.');
        if (body.reportedSystemId !== null && !systems.some((s) => s.id === body.reportedSystemId))
          mockError(400, 'INVALID_INPUT', '지원 시스템을 선택해 주세요.');
        const workspace = this.createWorkspace(
          user,
          body.content,
          body.reportedSystemId as string | null,
          body.requestId,
        );
        this.analyze(workspace);
        resource = { type: 'INQUIRY', id: workspace.result.id };
      } else if (parts[0] === 'inquiries' && parts[1]) {
        const workspace = this.workspace(user, parts[1]);
        if (body.expectedInquiryVersion !== workspace.result.inquiryVersion)
          mockError(409, 'INQUIRY_VERSION_CONFLICT', '최신 문의를 조회한 뒤 다시 확인해 주세요.');
        if (parts[4] === 'system-confirmations') {
          const problem = workspace.result.problems.find((p) => p.id === parts[3]);
          if (problem?.reportedSystemId !== null || !systems.some((s) => s.id === body.systemId))
            mockError(409, 'INVALID_STATE', '시스템 선택 대상을 확인해 주세요.');
          problem.reportedSystemId = String(body.systemId);
          problem.state = 'ANALYZING';
        } else if (parts[4] === 'problem-links') {
          if (
            !parts[3] ||
            !workspace.result.pendingTargetMessageIds.includes(parts[3]) ||
            !Array.isArray(body.problemIds) ||
            !body.problemIds.length ||
            !body.problemIds.every((id) => workspace.result.problems.some((p) => p.id === id))
          )
            mockError(409, 'INVALID_STATE', '보완 대상을 확인해 주세요.');
          const message = workspace.messages.find((m) => m.id === parts[3]);
          if (message) message.problemIds = body.problemIds as string[];
          workspace.result.pendingTargetMessageIds =
            workspace.result.pendingTargetMessageIds.filter((id) => id !== parts[3]);
        } else {
          if (typeof body.content !== 'string' || contentError(body.content))
            mockError(400, 'INVALID_INPUT', '메시지 내용을 확인해 주세요.');
          if (parts[4] === 'revisions') {
            const message = workspace.messages.find(
              (m) => m.id === parts[3] && m.sender === 'USER',
            );
            if (!message) mockError(404, 'NOT_FOUND', '정정할 메시지가 없어요.');
            if (body.expectedMessageRevision !== message.revision)
              mockError(409, 'MESSAGE_REVISION_CONFLICT', '최신 원문과 비교해 주세요.');
            message.revisions.push({
              revisionNo: message.revision,
              content: message.content,
              createdAt: message.createdAt,
            });
            message.revision++;
            message.content = body.content;
          } else {
            const message = this.message(workspace, body.content, 'USER', body.requestId);
            const assigned = workspace.result.problems.filter((p) => p.ticket);
            if (
              assigned.length &&
              Array.isArray(body.problemIds) &&
              !body.problemIds.length &&
              workspace.result.problems.length > 1
            ) {
              message.problemIds = [];
              workspace.result.pendingTargetMessageIds.push(message.id);
            }
          }
        }
        workspace.result.inquiryVersion++;
        if (
          workspace.result.problems.some((p) => !p.ticket && p.state !== 'OUT_OF_SCOPE') &&
          parts[4] !== 'problem-links'
        )
          this.analyze(workspace);
        resource = { type: 'INQUIRY', id: workspace.result.id };
      } else if (parts[0] === 'runs' && parts[1]) {
        const workspace = [...this.inquiries.values()].find(
          (w) => w.result.currentRun?.id === parts[1],
        );
        if (!workspace) mockError(404, 'NOT_FOUND', '실행을 찾을 수 없어요.');
        this.workspace(user, workspace.result.id);
        if (body.expectedInquiryVersion !== workspace.result.inquiryVersion)
          mockError(409, 'INQUIRY_VERSION_CONFLICT', '최신 문의를 조회해 주세요.');
        if (!['FAILED', 'TIMED_OUT'].includes(workspace.result.currentRun?.status ?? ''))
          mockError(409, 'RUN_NOT_RETRYABLE', '재분석할 수 없는 실행이에요.');
        resource = { type: 'RUN', id: this.analyze(workspace).id };
      } else if (parts[0] === 'work-items' && parts[1]) {
        const workspace = [...this.inquiries.values()].find((w) =>
          w.result.problems.some((p) => p.id === parts[1]),
        );
        if (!workspace) mockError(404, 'NOT_FOUND', '작업을 찾을 수 없어요.');
        this.workspace(user, workspace.result.id);
        const problem = workspace.result.problems.find((p) => p.id === parts[1]);
        if (body.expectedInquiryVersion !== workspace.result.inquiryVersion)
          mockError(409, 'INQUIRY_VERSION_CONFLICT', '최신 문의를 조회해 주세요.');
        if (!problem || problem.creationResult?.outcome !== 'NOT_CREATED')
          mockError(409, 'WORK_ITEM_NOT_RETRYABLE', '저장 결과 확인이 필요해요.');
        this.storeTicket(workspace, problem);
        resource = { type: 'WORK_ITEM', id: problem.id };
      } else mockError(404, 'NOT_FOUND', '지원하지 않는 변경이에요.');
      const receipt: D.CommandReceipt = {
        requestId: body.requestId,
        operationId: crypto.randomUUID(),
        state: 'SUCCEEDED',
        resource,
        error: null,
      };
      this.journal.set(journalKey, { digest, userId: user.id, createdAt: Date.now(), receipt });
      this.changed();
      return clone(receipt);
    }
    const number = (name: string, fallback: number) => {
      const raw = url.searchParams.get(name);
      if (raw !== null && !/^\d+$/u.test(raw))
        mockError(400, 'INVALID_INPUT', '조회 조건을 확인해 주세요.');
      return raw === null ? fallback : Number(raw);
    };
    const page = number('page', 0);
    const size = number(
      'size',
      parts.includes('messages') || parts.includes('related-messages') ? 30 : 20,
    );
    if (!Number.isSafeInteger(page) || size < 1 || size > 100)
      mockError(400, 'INVALID_INPUT', '페이지 범위를 확인해 주세요.');
    const workflowResult = this.workflow.get(user, parts, url.searchParams);
    if (workflowResult) return clone(workflowResult.value);
    if (route === '/inquiries') {
      const items: D.InquirySummary[] = [...this.inquiries.values()]
        .filter((w) => w.ownerId === user.id)
        .map(({ result: r, messages }) => ({
          id: r.id,
          inquiryNumber: r.inquiryNumber,
          title: messages[0]?.content ?? '문의',
          status: r.status,
          problemCount: r.problems.length,
          ticketCount: r.problems.filter((p) => p.ticket).length,
          pendingConfirmationCount: r.problems.filter(
            (p) => p.activeResolutionRequest?.state === 'PENDING',
          ).length,
          createdAt: r.createdAt,
          updatedAt: r.updatedAt,
        }))
        .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt) || b.id.localeCompare(a.id));
      return clone({
        items: items.slice(page * size, (page + 1) * size),
        page,
        size,
        totalElements: items.length,
      });
    }
    if (parts[0] === 'inquiries' && parts[1]) {
      const workspace = this.workspace(user, parts[1]);
      if (parts[2] !== 'messages') return clone(workspace.result);
      const before = url.searchParams.has('beforeSequenceNo')
        ? number('beforeSequenceNo', 0)
        : Infinity;
      if (before < 1) mockError(400, 'INVALID_INPUT', '메시지 커서를 확인해 주세요.');
      const all = workspace.messages.filter((m) => m.sequenceNo < before);
      const items = all.slice(-size);
      return clone({
        items,
        nextBeforeSequenceNo: all.length > items.length ? (items[0]?.sequenceNo ?? null) : null,
      });
    }
    if (route === '/tickets') {
      const admin = url.searchParams.get('scope') === 'ADMIN';
      const systemId = url.searchParams.get('systemId');
      const state = url.searchParams.get('state');
      if (admin ? !user.roles.includes('BIZ_ADMIN') : !user.primarySystemIds.length)
        mockError(403, 'ACCESS_DENIED', '업무 조회 권한이 없어요.');
      if (
        systemId &&
        !(admin ? systems.some((s) => s.id === systemId) : user.primarySystemIds.includes(systemId))
      )
        mockError(403, 'ACCESS_DENIED', '조회할 수 없는 시스템이에요.');
      if (state && !['WAITING', 'IN_PROGRESS', 'WAITING_CONFIRMATION', 'RESOLVED'].includes(state))
        mockError(400, 'INVALID_INPUT', '상태를 확인해 주세요.');
      const items = [...this.details.values()]
        .filter(
          (t) =>
            (admin || user.primarySystemIds.includes(t.responsibleSystem.id)) &&
            (!systemId || t.responsibleSystem.id === systemId) &&
            (!state || t.state === state),
        )
        .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt) || b.id.localeCompare(a.id))
        .map((t) => this.summary(t));
      return clone({
        items: items.slice(page * size, (page + 1) * size),
        page,
        size,
        totalElements: items.length,
      });
    }
    if (parts[0] === 'management' && parts[2]) {
      const t = this.ticket(user, parts[2], true);
      return clone({
        ...this.summary(t),
        currentPrimary: t.currentPrimary,
        assignmentHistory: t.assignmentHistory,
      });
    }
    if (parts[0] === 'tickets' && parts[1]) {
      const ticket = this.ticket(user, parts[1]);
      if (parts[2] === 'related-messages') {
        const all =
          this.inquiries
            .get(ticket.inquiryId)
            ?.messages.filter((m) => m.problemIds.includes(ticket.problemId)) ?? [];
        // 복합 원문의 부분 투영은 이 메모리 대역에서 안전하게 수행할 수 없으므로 공유 메시지는 제외한다.
        const safe = all
          .filter((m) => m.problemIds.length === 1)
          .map(({ id, revision, sender, content, revisions, createdAt }) => ({
            id,
            revision,
            sender,
            content,
            revisions,
            createdAt,
          }));
        const offset = number('cursor', 0);
        return clone({
          items: safe.slice(offset, offset + size),
          nextCursor: safe.length > offset + size ? String(offset + size) : null,
        });
      }
      return clone(ticket);
    }
    if (parts[0] === 'runs' && parts[1]) {
      const workspace = [...this.inquiries.values()].find(
        (w) => w.result.currentRun?.id === parts[1],
      );
      if (!workspace) mockError(404, 'NOT_FOUND', '실행을 찾을 수 없어요.');
      this.workspace(user, workspace.result.id);
      return clone(workspace.result.currentRun);
    }
    if (parts[0] === 'work-items' && parts[1]) {
      const workspace = [...this.inquiries.values()].find((w) =>
        w.result.problems.some((p) => p.id === parts[1]),
      );
      if (!workspace) mockError(404, 'NOT_FOUND', '작업을 찾을 수 없어요.');
      this.workspace(user, workspace.result.id);
      return clone(workspace.result.problems.find((p) => p.id === parts[1])?.creationResult);
    }
    mockError(404, 'NOT_FOUND', '지원하지 않는 API예요.');
  }
  private summary(t: D.TicketDetail): D.TicketSummary {
    const {
      id,
      inquiryId,
      problemId,
      workItemId,
      ticketNumber,
      title,
      processingType,
      reportedSystem,
      responsibleSystem,
      assignee,
      state,
      ticketRevision,
      createdAt,
      updatedAt,
    } = t;
    return {
      id,
      inquiryId,
      problemId,
      workItemId,
      ticketNumber,
      title,
      processingType,
      reportedSystem,
      responsibleSystem,
      assignee,
      state,
      ticketRevision,
      createdAt,
      updatedAt,
    };
  }
  private aggregate(workspace: Workspace) {
    const states = workspace.result.problems.map((p) => p.state);
    workspace.result.status = !states.length
      ? 'IN_PROGRESS'
      : states.every((s) => s === 'OUT_OF_SCOPE')
        ? 'CLOSED'
        : states.every((s) => ['RESOLVED', 'OUT_OF_SCOPE'].includes(s))
          ? 'RESOLVED'
          : states.some((s) => ['ANALYZING', 'TICKET_PENDING', 'IN_SERVICE'].includes(s))
            ? 'IN_PROGRESS'
            : 'WAITING_USER';
    workspace.result.updatedAt = now();
  }
  private syncTicket(ticket: D.TicketDetail) {
    const workspace = this.inquiries.get(ticket.inquiryId);
    const problem = workspace?.result.problems.find((p) => p.id === ticket.problemId);
    if (!workspace || !problem) return;
    problem.ticket = this.summary(ticket);
    problem.processingType = ticket.processingType;
    problem.activeResolutionRequest = ticket.activeResolutionRequest;
    problem.state =
      ticket.state === 'RESOLVED'
        ? 'RESOLVED'
        : ticket.state === 'WAITING_CONFIRMATION'
          ? 'WAITING_CONFIRMATION'
          : 'IN_SERVICE';
    this.aggregate(workspace);
  }
}
export const systemMockBackend = new SystemMockBackend();
