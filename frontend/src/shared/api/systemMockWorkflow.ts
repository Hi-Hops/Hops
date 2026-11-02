import type * as D from './types/hopsSystem1';
import { contentError } from '@/shared/lib/content';
import { isUuid } from './wire';
import { mockError } from './mockError';
import { systems } from './systemMockData';
import type { MockExampleStage } from './systemMockExamples';

type Context = {
  people: () => D.CurrentUser[];
  ticket: (user: D.CurrentUser, id: string, admin?: boolean) => D.TicketDetail;
  findTicket: (id: string) => D.TicketDetail | undefined;
  inquiryOwner: (id: string) => string | undefined;
  sync: (ticket: D.TicketDetail) => void;
  guideResponse: (request: D.ResolutionRequest) => void;
  evidenceAllowed: (ticket: D.TicketDetail, ids: string[]) => boolean;
  changed: () => void;
};
const now = () => new Date().toISOString();
function text(body: Record<string, unknown>, field: string): string {
  const value = body[field];
  if (typeof value !== 'string' || contentError(value))
    mockError(400, 'INVALID_INPUT', '각 입력 항목을 1~4,000자로 작성해 주세요.');
  return value;
}
function optional(body: Record<string, unknown>, field: string): string | null {
  return body[field] === null ? null : text(body, field);
}
function version(body: Record<string, unknown>, field: string, expected: number, code: string) {
  const value = body[field];
  if (!Number.isInteger(value) || Number(value) < 1)
    mockError(400, 'INVALID_INPUT', '버전을 확인해 주세요.');
  if (value !== expected) mockError(409, code, '최신 정보와 작성 내용을 비교해 주세요.');
}
export class SystemMockWorkflow {
  private seeding = false;
  private cases = new Map<string, D.CaseDetail>();
  private generations = new Map<string, { value: D.CaseGeneration; ticketId: string }>();
  private resolutions = new Map<string, D.ResolutionRequest>();
  private timers = new Set<ReturnType<typeof setTimeout>>();
  private nextGeneration = 'SUCCEEDED';
  private nextIndexFailure: D.IndexStore | null = null;
  private processingHistory: {
    ticketId: string;
    actorId: string;
    reason: string;
    evidenceNote: string | null;
    before: string;
    after: string;
    createdAt: string;
  }[] = [];
  constructor(private context: Context) {}
  reset() {
    for (const timer of this.timers) clearTimeout(timer);
    this.timers.clear();
    this.cases.clear();
    this.generations.clear();
    this.resolutions.clear();
    this.processingHistory = [];
    this.nextGeneration = 'SUCCEEDED';
    this.nextIndexFailure = null;
  }
  setGenerationOutcome(value: 'FAILED' | 'NO_REUSABLE_CASE' | 'SUCCEEDED') {
    this.nextGeneration = value;
  }
  failIndex(store: D.IndexStore) {
    this.nextIndexFailure = store;
  }
  seedExample(ticket: D.TicketDetail, requester: D.CurrentUser, stage: MockExampleStage) {
    const staff = this.context.people().find((p) => p.id === ticket.currentPrimary.id);
    if (!staff) throw new Error('가상 시나리오 담당자 누락');
    this.seeding = true;
    try {
      if (stage === 'GENERATION_FAILED') this.setGenerationOutcome('FAILED');
      if (stage === 'NO_REUSABLE_CASE') this.setGenerationOutcome('NO_REUSABLE_CASE');
      this.post(staff, ['tickets', ticket.id, 'work-records'], {
        requestId: crypto.randomUUID(),
        expectedTicketRevision: ticket.ticketRevision,
        kind:
          stage === 'PROGRESS'
            ? 'PROGRESS'
            : ticket.processingType === 'CHANGE_REQUEST'
              ? 'IMPLEMENTED_VERIFIED'
              : 'VERIFIED_RESOLUTION',
        action:
          stage === 'PROGRESS'
            ? '가상 문의의 발생 조건과 접근 정책을 점검하고 있어요.'
            : '가상 환경의 접근 정책을 복구하고 요청 동작을 적용했어요.',
        verification:
          stage === 'PROGRESS'
            ? null
            : '가상 시나리오에서 동일 사용자·화면의 정상 동작을 확인했어요.',
        confirmedCause: null,
        appliesToSystemVersion: 'demo-1.0',
      });
      const request = ticket.activeResolutionRequest;
      if (
        stage === 'PROGRESS' ||
        stage === 'WAITING_CONFIRMATION' ||
        stage === 'GENERATION_FAILED' ||
        stage === 'NO_REUSABLE_CASE' ||
        !request
      )
        return;
      this.post(requester, ['resolution-requests', request.id, 'responses'], {
        requestId: crypto.randomUUID(),
        expectedRequestRevision: request.requestRevision,
        decision: 'RESOLVED',
      });
      const caseId = ticket.caseGeneration?.caseId;
      if (!caseId || stage === 'DRAFT_READY') return;
      if (stage === 'INDEX_FAILED') this.failIndex('NEO4J');
      this.post(staff, ['cases', caseId, 'approvals'], {
        requestId: crypto.randomUUID(),
        expectedCaseVersion: 1,
      });
    } finally {
      this.seeding = false;
    }
  }
  private later(action: () => void) {
    // 최초 시연 데이터 구성만 즉시 완료한다. 사용자 명령은 기존 비동기 흐름을 유지한다.
    if (this.seeding) {
      action();
      return;
    }
    const timer = setTimeout(() => {
      this.timers.delete(timer);
      action();
      this.context.changed();
    }, 180);
    this.timers.add(timer);
  }
  private primary(id: string): D.PrimaryAssignment {
    const person = this.context.people().find((p) => p.primarySystemIds.includes(id));
    if (!person) mockError(503, 'ASSIGNMENT_UNAVAILABLE', '현재 주담당자를 확인하지 못했어요.');
    return {
      systemId: id,
      primaryUser: { id: person.id, name: person.name, department: person.department },
    };
  }
  private caseFor(user: D.CurrentUser, id: string) {
    const item = this.cases.get(id);
    if (!item) mockError(404, 'NOT_FOUND', '사례를 찾을 수 없어요.');
    if (!user.primarySystemIds.includes(item.version.systemId))
      mockError(403, 'ACCESS_DENIED', '이 사례의 현재 담당자가 아니에요.');
    return item;
  }
  authorizeCase(user: D.CurrentUser, id: string) {
    this.caseFor(user, id);
  }
  private resolutionFor(user: D.CurrentUser, id: string) {
    const item = this.resolutions.get(id);
    if (!item) mockError(404, 'NOT_FOUND', '확인 요청을 찾을 수 없어요.');
    if (this.context.inquiryOwner(item.inquiryId) !== user.id) {
      if (!item.ticketId) mockError(403, 'ACCESS_DENIED', '본인의 확인 요청이 아니에요.');
      this.context.ticket(user, item.ticketId);
    }
    return item;
  }
  authorizeResource(user: D.CurrentUser, ref: D.ResourceRef | null) {
    if (!ref) return;
    if (ref.type === 'CASE') this.caseFor(user, ref.id);
    if (ref.type === 'RESOLUTION_REQUEST') this.resolutionFor(user, ref.id);
    if (ref.type === 'TICKET') this.context.ticket(user, ref.id, user.roles.includes('BIZ_ADMIN'));
    if (ref.type === 'CASE_GENERATION') {
      const generation = this.generations.get(ref.id);
      if (!generation) mockError(404, 'NOT_FOUND', '생성 작업을 찾을 수 없어요.');
      this.context.ticket(user, generation.ticketId);
    }
  }
  guide(problem: D.Problem, messageId: string): D.ResolutionRequest {
    const request: D.ResolutionRequest = {
      id: crypto.randomUUID(),
      inquiryId: problem.inquiryId,
      problemId: problem.id,
      ticketId: null,
      source: 'GUIDE',
      sourceId: messageId,
      sourceVersion: 1,
      requestRevision: 1,
      state: 'PENDING',
      resultSummary: 'Mock 직원용 안내를 확인한 뒤 이 문제의 해결 여부를 알려 주세요.',
      confirmationOrigin: 'HOPS_USER',
      createdAt: now(),
      respondedAt: null,
    };
    this.resolutions.set(request.id, request);
    problem.activeResolutionRequest = request;
    problem.guide = { messageId, evidence: [] };
    problem.state = 'WAITING_CONFIRMATION';
    return request;
  }
  private generate(
    ticket: D.TicketDetail,
    record: D.WorkRecord,
    resolution: D.ResolutionRequest,
    generation?: D.CaseGeneration,
  ) {
    const value: D.CaseGeneration = generation ?? {
      id: crypto.randomUUID(),
      workRecordId: record.id,
      state: 'PENDING',
      caseId: null,
      error: null,
    };
    value.state = 'RUNNING';
    value.error = null;
    this.generations.set(value.id, { value, ticketId: ticket.id });
    ticket.caseGeneration = value;
    const outcome = this.nextGeneration;
    this.nextGeneration = 'SUCCEEDED';
    this.later(() => {
      value.state = outcome;
      if (outcome === 'FAILED') {
        value.error = {
          code: 'MODEL_UNAVAILABLE',
          message: 'Mock 초안 생성 실패예요. 처리 기록은 유지돼요.',
          requestId: crypto.randomUUID(),
          retryable: true,
          fieldErrors: [],
        };
      } else if (outcome === 'SUCCEEDED') {
        const id = crypto.randomUUID();
        value.caseId = id;
        const detail: D.CaseDetail = {
          version: {
            id,
            caseVersion: 1,
            state: 'DRAFT',
            ticketId: ticket.id,
            problemId: ticket.problemId,
            workRecordId: record.id,
            resolutionRequestId: resolution.id,
            systemId: ticket.responsibleSystem.id,
            caseType: record.kind === 'IMPLEMENTED_VERIFIED' ? 'IMPLEMENTATION' : 'RESOLUTION',
            problemStatement: ticket.standardRequest.summary,
            confirmedCause: record.confirmedCause,
            title: ticket.title + ' 처리 사례',
            action: record.action,
            verification: record.verification ?? '',
            applicability: '동일 시스템·증상과 실제 적용 조건을 확인한 뒤 참고하세요.',
            limitations: ['가상 개발 데이터이며 현재 장애 원인을 확정하지 않아요.'],
            appliesToSystemVersion: record.appliesToSystemVersion,
            accessScope: 'SYSTEM_STAFF',
            origin: 'SYNTHETIC',
            confirmationOrigin: resolution.confirmationOrigin,
            sourceMessageIds: ticket.standardRequest.confirmedFacts.flatMap(
              (f) => f.sourceMessageIds,
            ),
            createdAt: now(),
          },
          approval: null,
          indexes: [],
          publication: { caseId: id, caseVersion: 1, state: 'PRIVATE', canSearch: false },
        };
        this.cases.set(id, detail);
      }
    });
    return value;
  }
  private refreshPublication(detail: D.CaseDetail) {
    const version = detail.version;
    const visible =
      detail.approval?.caseVersion === version.caseVersion &&
      ['VECTOR', 'NEO4J'].every((store) => {
        const matches = detail.indexes.filter(
          (index) =>
            index.caseId === version.id &&
            index.caseVersion === version.caseVersion &&
            index.store === store,
        );
        return matches.length === 1 && matches[0]?.state === 'SUCCEEDED';
      });
    detail.publication = {
      caseId: version.id,
      caseVersion: version.caseVersion,
      state: visible ? 'SEARCHABLE' : 'PRIVATE',
      canSearch: visible,
    };
  }
  private runIndex(detail: D.CaseDetail, index: D.IndexResult) {
    const fail = this.nextIndexFailure === index.store;
    if (fail) this.nextIndexFailure = null;
    const attempt = index.attempt;
    index.state = 'RUNNING';
    this.later(() => {
      if (index.attempt !== attempt || index.caseVersion !== detail.version.caseVersion) return;
      index.state = fail ? 'FAILED' : 'SUCCEEDED';
      index.error = fail
        ? {
            code: 'SEARCH_UNAVAILABLE',
            message: 'Mock 반영 실패예요.',
            requestId: crypto.randomUUID(),
            retryable: true,
            fieldErrors: [],
          }
        : null;
      index.updatedAt = now();
      this.refreshPublication(detail);
    });
  }
  post(user: D.CurrentUser, parts: string[], body: Record<string, unknown>): D.ResourceRef | null {
    const id = parts[1];
    const action = parts[2];
    if (!id) return null;
    if (parts[0] === 'tickets') {
      const admin = action === 'assignment-corrections' && user.roles.includes('BIZ_ADMIN');
      const ticket = this.context.ticket(user, id, admin);
      version(body, 'expectedTicketRevision', ticket.ticketRevision, 'TICKET_REVISION_CONFLICT');
      if (!['WAITING', 'IN_PROGRESS', 'WAITING_CONFIRMATION'].includes(ticket.state))
        mockError(409, 'INVALID_STATE', '현재 상태에서는 수정할 수 없어요.');
      if (action === 'work-records') {
        const kind = String(body.kind);
        if (
          !['PROGRESS', 'FAILED_ATTEMPT', 'VERIFIED_RESOLUTION', 'IMPLEMENTED_VERIFIED'].includes(
            kind,
          )
        )
          mockError(400, 'INVALID_INPUT', '기록 유형을 확인해 주세요.');
        const verified = ['VERIFIED_RESOLUTION', 'IMPLEMENTED_VERIFIED'].includes(kind);
        if (
          verified &&
          (ticket.state === 'WAITING_CONFIRMATION' ||
            (ticket.processingType === 'CHANGE_REQUEST'
              ? kind !== 'IMPLEMENTED_VERIFIED'
              : kind !== 'VERIFIED_RESOLUTION'))
        )
          mockError(409, 'INVALID_STATE', '새 검증 기록을 저장할 수 없는 상태예요.');
        const verification = verified ? text(body, 'verification') : optional(body, 'verification');
        const record: D.WorkRecord = {
          id: crypto.randomUUID(),
          ticketId: id,
          kind,
          action: text(body, 'action'),
          verification,
          confirmedCause: optional(body, 'confirmedCause'),
          appliesToSystemVersion: optional(body, 'appliesToSystemVersion'),
          author: { id: user.id, name: user.name, department: user.department },
          createdAt: now(),
        };
        ticket.workRecords.push(record);
        if (verified) {
          const request: D.ResolutionRequest = {
            id: crypto.randomUUID(),
            inquiryId: ticket.inquiryId,
            problemId: ticket.problemId,
            ticketId: id,
            source: 'WORK_RECORD',
            sourceId: record.id,
            sourceVersion: 1,
            requestRevision: 1,
            state: 'PENDING',
            resultSummary: record.action + '\n검증: ' + (record.verification ?? ''),
            confirmationOrigin: 'HOPS_USER',
            createdAt: now(),
            respondedAt: null,
          };
          this.resolutions.set(request.id, request);
          ticket.activeResolutionRequest = request;
          ticket.state = 'WAITING_CONFIRMATION';
          this.generate(ticket, record, request);
        } else if (ticket.state === 'WAITING') ticket.state = 'IN_PROGRESS';
      } else if (action === 'assignment-corrections' || action === 'processing-type-corrections') {
        const reason = text(body, 'reason');
        const evidenceNote = optional(body, 'evidenceNote');
        const ids = body.evidenceMessageIds;
        if (!Array.isArray(ids) || !ids.every(isUuid) || (!ids.length && !evidenceNote))
          mockError(400, 'INVALID_INPUT', '정정 근거를 입력해 주세요.');
        const evidenceIds = ids.filter((value): value is string => typeof value === 'string');
        if (
          evidenceIds.length &&
          (!user.primarySystemIds.includes(ticket.responsibleSystem.id) ||
            !this.context.evidenceAllowed(ticket, evidenceIds))
        )
          mockError(403, 'ACCESS_DENIED', '조회할 수 없는 메시지 근거예요.');
        if (action === 'assignment-corrections') {
          const system = systems.find((s) => s.id === body.responsibleSystemId);
          if (!system) mockError(400, 'INVALID_INPUT', '책임 시스템을 확인해 주세요.');
          const primary = this.primary(system.id);
          ticket.assignmentHistory.push({
            id: crypto.randomUUID(),
            previousSystemId: ticket.responsibleSystem.id,
            previousAssigneeId: ticket.assignee.id,
            systemId: system.id,
            assignee: primary.primaryUser,
            reason,
            evidenceMessageIds: evidenceIds,
            evidenceNote,
            actorId: user.id,
            createdAt: now(),
          });
          ticket.responsibleSystem = system;
          ticket.currentPrimary = primary.primaryUser;
          ticket.assignee = primary.primaryUser;
        } else {
          if (
            !['INVESTIGATION', 'INCIDENT'].includes(ticket.processingType) ||
            !['INVESTIGATION', 'INCIDENT'].includes(String(body.processingType)) ||
            body.processingType === ticket.processingType
          )
            mockError(409, 'INVALID_STATE', '조사와 장애 사이에서만 정정할 수 있어요.');
          this.processingHistory.push({
            ticketId: id,
            actorId: user.id,
            reason,
            evidenceNote,
            before: ticket.processingType,
            after: String(body.processingType),
            createdAt: now(),
          });
          ticket.processingType = String(body.processingType);
        }
      } else return null;
      ticket.ticketRevision++;
      ticket.updatedAt = now();
      this.context.sync(ticket);
      return { type: 'TICKET', id };
    }
    if (parts[0] === 'resolution-requests' && action === 'responses') {
      const request = this.resolutions.get(id);
      if (!request) mockError(404, 'NOT_FOUND', '확인 요청을 찾을 수 없어요.');
      if (this.context.inquiryOwner(request.inquiryId) !== user.id)
        mockError(403, 'ACCESS_DENIED', '문의자만 응답할 수 있어요.');
      version(
        body,
        'expectedRequestRevision',
        request.requestRevision,
        'RESOLUTION_REVISION_CONFLICT',
      );
      if (request.state !== 'PENDING')
        mockError(409, 'INVALID_STATE', '이미 응답한 확인 요청이에요.');
      if (body.decision !== 'RESOLVED' && body.decision !== 'UNRESOLVED')
        mockError(400, 'INVALID_INPUT', '해결 응답을 확인해 주세요.');
      request.state = body.decision;
      request.confirmationOrigin = this.seeding ? 'SYNTHETIC_PRESET' : 'HOPS_USER';
      for (const detail of this.cases.values()) {
        if (detail.version.resolutionRequestId === request.id && detail.version.state === 'DRAFT')
          detail.version.confirmationOrigin = request.confirmationOrigin;
      }
      request.requestRevision++;
      request.respondedAt = now();
      const ticket = request.ticketId ? this.context.findTicket(request.ticketId) : undefined;
      if (ticket) {
        ticket.state = request.state === 'RESOLVED' ? 'RESOLVED' : 'IN_PROGRESS';
        ticket.ticketRevision++;
        ticket.updatedAt = now();
        this.context.sync(ticket);
      } else this.context.guideResponse(request);
      return { type: 'RESOLUTION_REQUEST', id };
    }
    if (parts[0] === 'case-generations' && action === 'retries') {
      const item = this.generations.get(id);
      if (!item) mockError(404, 'NOT_FOUND', '초안 생성을 찾을 수 없어요.');
      const ticket = this.context.ticket(user, item.ticketId);
      if (item.value.state !== 'FAILED')
        mockError(409, 'INVALID_STATE', '실패한 초안만 다시 요청할 수 있어요.');
      const record = ticket.workRecords.find((r) => r.id === item.value.workRecordId);
      const resolution = [...this.resolutions.values()].find((r) => r.sourceId === record?.id);
      if (!record || !resolution) mockError(409, 'INVALID_STATE', '원 기록을 확인하지 못했어요.');
      this.generate(ticket, record, resolution, item.value);
      return { type: 'CASE_GENERATION', id };
    }
    if (parts[0] === 'cases') {
      const detail = this.caseFor(user, id);
      const current = detail.version;
      version(body, 'expectedCaseVersion', current.caseVersion, 'CASE_VERSION_CONFLICT');
      if (action === 'versions') {
        if (current.state !== 'DRAFT')
          mockError(409, 'INVALID_STATE', '승인된 사례는 편집할 수 없어요.');
        const limitations = body.limitations;
        if (
          !Array.isArray(limitations) ||
          !limitations.every((line) => typeof line === 'string' && !contentError(line)) ||
          !['EMPLOYEE', 'SYSTEM_STAFF'].includes(String(body.accessScope))
        )
          mockError(400, 'INVALID_INPUT', '사례 입력을 확인해 주세요.');
        detail.version = {
          ...current,
          caseVersion: current.caseVersion + 1,
          title: text(body, 'title'),
          problemStatement: text(body, 'problemStatement'),
          action: text(body, 'action'),
          verification: text(body, 'verification'),
          applicability: text(body, 'applicability'),
          limitations: limitations.filter((line): line is string => typeof line === 'string'),
          appliesToSystemVersion: optional(body, 'appliesToSystemVersion'),
          accessScope: body.accessScope === 'EMPLOYEE' ? 'EMPLOYEE' : 'SYSTEM_STAFF',
          createdAt: now(),
        };
        detail.publication = {
          caseId: id,
          caseVersion: detail.version.caseVersion,
          state: 'PRIVATE',
          canSearch: false,
        };
      } else if (action === 'approvals') {
        if (
          current.state !== 'DRAFT' ||
          this.resolutions.get(current.resolutionRequestId)?.state !== 'RESOLVED'
        )
          mockError(409, 'APPROVAL_PRECONDITION_FAILED', '원 기록의 문의자 해결 확인이 필요해요.');
        current.state = 'APPROVED';
        detail.approval = {
          caseId: id,
          caseVersion: current.caseVersion,
          approvedBy: { id: user.id, name: user.name, department: user.department },
          approvedAt: now(),
        };
        detail.indexes = (['VECTOR', 'NEO4J'] as const).map((store) => ({
          jobId: crypto.randomUUID(),
          caseId: id,
          caseVersion: current.caseVersion,
          store,
          state: 'PENDING',
          attempt: 1,
          error: null,
          updatedAt: now(),
        }));
        detail.indexes.forEach((index) => {
          this.runIndex(detail, index);
        });
      } else if (action === 'index-checks' || action === 'index-retries') {
        if (current.state !== 'APPROVED')
          mockError(409, 'INVALID_STATE', '승인된 버전이 아니에요.');
        const index = detail.indexes.find(
          (item) => item.caseVersion === current.caseVersion && item.store === body.store,
        );
        if (!index) mockError(400, 'INVALID_INPUT', '반영 대상을 확인해 주세요.');
        if (action === 'index-retries') {
          if (index.state !== 'FAILED')
            mockError(409, 'INDEX_NOT_RETRYABLE', '실패한 저장소만 재실행할 수 있어요.');
          index.attempt++;
          index.error = null;
          this.runIndex(detail, index);
        } else {
          if (!['PENDING', 'RUNNING', 'UNKNOWN'].includes(index.state))
            mockError(409, 'INVALID_STATE', '현재 반영은 확인 대상이 아니에요.');
          index.state = 'SUCCEEDED';
          index.updatedAt = now();
          index.error = null;
        }
        this.refreshPublication(detail);
      } else return null;
      return { type: 'CASE', id };
    }
    return null;
  }
  get(
    user: D.CurrentUser,
    parts: string[],
    search: URLSearchParams,
  ): { found: true; value: unknown } | null {
    const id = parts[1];
    if (parts[0] === 'systems' && id && parts[2] === 'primary') {
      if (!user.primarySystemIds.length && !user.roles.includes('BIZ_ADMIN'))
        mockError(403, 'ACCESS_DENIED', '담당 정보 조회 권한이 없어요.');
      return { found: true, value: this.primary(id) };
    }
    if (parts[0] === 'resolution-requests' && id)
      return { found: true, value: this.resolutionFor(user, id) };
    if (parts[0] === 'case-generations' && id) {
      const item = this.generations.get(id);
      if (!item) mockError(404, 'NOT_FOUND', '초안 생성 작업이 없어요.');
      this.context.ticket(user, item.ticketId);
      return { found: true, value: item.value };
    }
    if (parts[0] === 'cases') {
      if (id) {
        const detail = this.caseFor(user, id);
        if (
          search.has('caseVersion') &&
          search.get('caseVersion') !== String(detail.version.caseVersion)
        )
          mockError(404, 'NOT_FOUND', '현재 버전만 조회할 수 있어요.');
        return { found: true, value: detail };
      }
      if (!user.primarySystemIds.length)
        mockError(403, 'ACCESS_DENIED', '사례 검토 권한이 없어요.');
      const systemId = search.get('systemId');
      const state = search.get('state');
      if (systemId && !user.primarySystemIds.includes(systemId))
        mockError(403, 'ACCESS_DENIED', '담당 시스템만 조회할 수 있어요.');
      if (state && !['DRAFT', 'APPROVED'].includes(state))
        mockError(400, 'INVALID_INPUT', '사례 상태를 확인해 주세요.');
      const page = Number(search.get('page') ?? '0');
      const size = Number(search.get('size') ?? '20');
      if (
        !Number.isSafeInteger(page) ||
        page < 0 ||
        !Number.isSafeInteger(size) ||
        size < 1 ||
        size > 100
      )
        mockError(400, 'INVALID_INPUT', '페이지 범위를 확인해 주세요.');
      const items: D.CaseSummary[] = [...this.cases.values()]
        .filter(
          (item) =>
            user.primarySystemIds.includes(item.version.systemId) &&
            (!systemId || item.version.systemId === systemId) &&
            (!state || item.version.state === state),
        )
        .map((item) => ({
          id: item.version.id,
          title: item.version.title,
          systemId: item.version.systemId,
          latestCaseVersion: item.version.caseVersion,
          latestState: item.version.state,
          publishedVersions:
            item.publication.state === 'SEARCHABLE' ? [item.version.caseVersion] : [],
          generationState: 'SUCCEEDED',
          updatedAt: item.approval?.approvedAt ?? item.version.createdAt,
        }))
        .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt) || b.id.localeCompare(a.id));
      return {
        found: true,
        value: {
          items: items.slice(page * size, (page + 1) * size),
          page,
          size,
          totalElements: items.length,
        },
      };
    }
    return null;
  }
}
