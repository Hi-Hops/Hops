import { isRecord, isUuid } from './wire';
import type * as D from './types/hopsSystem1';

type Check = (value: unknown) => boolean;
const str: Check = (v) => typeof v === 'string' && v.trim().length > 0;
const text: Check = (v) => typeof v === 'string';
const integer: Check = (v) => Number.isInteger(v) && Number(v) >= 0 && Number(v) <= 2147483647;
const positive: Check = (v) => integer(v) && Number(v) > 0;
const time: Check = (v) =>
  typeof v === 'string' && v.endsWith('Z') && Number.isFinite(Date.parse(v));
const bool: Check = (v) => typeof v === 'boolean';
const nullable =
  (check: Check): Check =>
  (v) =>
    v === null || check(v);
const list =
  (check: Check): Check =>
  (v) =>
    Array.isArray(v) && v.every(check);
const one =
  (...values: string[]): Check =>
  (v) =>
    typeof v === 'string' && values.includes(v);
const obj =
  (fields: Record<string, Check>): Check =>
  (v) =>
    isRecord(v) && Object.entries(fields).every(([key, check]) => key in v && check(v[key]));
const department = obj({ id: isUuid, code: str, name: str });
const user = obj({ id: isUuid, name: str, department });
const currentUser = obj({
  id: isUuid,
  name: str,
  department,
  employeeNo: str,
  roles: list(one('BIZ_ADMIN')),
  primarySystemIds: list(isUuid),
});
const system = obj({
  id: isUuid,
  code: one('ELECTRONIC_APPROVAL', 'COMMON_LOGIN', 'FILE_SHARING'),
  name: str,
  features: list(obj({ id: isUuid, name: str })),
});
const error = obj({
  code: str,
  message: str,
  requestId: isUuid,
  retryable: bool,
  fieldErrors: list(obj({ field: str, code: str })),
});
export function isSystemErrorBody(value: unknown): value is D.ApiErrorBody {
  return error(value);
}
const resource = obj({
  type: one(
    'INQUIRY',
    'MESSAGE',
    'RUN',
    'TICKET',
    'WORK_ITEM',
    'WORK_RECORD',
    'RESOLUTION_REQUEST',
    'CASE',
    'INDEX_JOB',
    'CASE_GENERATION',
    'IMAGE_ATTACHMENT',
  ),
  id: isUuid,
});
const receiptShape = obj({
  requestId: isUuid,
  operationId: isUuid,
  state: one('PENDING', 'SUCCEEDED', 'REJECTED', 'UNKNOWN'),
  resource: nullable(resource),
  error: nullable(error),
});
const receipt: Check = (v) =>
  receiptShape(v) &&
  isRecord(v) &&
  (v.state !== 'SUCCEEDED' || (v.resource !== null && v.error === null)) &&
  (v.state !== 'REJECTED' || v.error !== null);
const revision = obj({ revisionNo: positive, content: str, createdAt: time });
const related = obj({
  id: isUuid,
  revision: positive,
  sender: one('USER', 'AI'),
  content: str,
  revisions: list(revision),
  createdAt: time,
});
const messageShape = obj({
  id: isUuid,
  inquiryId: isUuid,
  requestId: nullable(isUuid),
  revision: positive,
  sequenceNo: positive,
  sender: one('USER', 'AI'),
  content: str,
  problemIds: list(isUuid),
  revisions: list(revision),
  createdAt: time,
});
const imageAttachment = obj({
  id: isUuid,
  mediaType: one('image/png'),
  byteSize: (value) => positive(value) && Number(value) <= 8 * 1024 * 1024,
});
const imageObservation = obj({
  observationId: isUuid,
  observationRevision: positive,
  attachmentId: isUuid,
  problemId: isUuid,
  text: (value) => str(value) && Array.from(String(value)).length <= 400,
  state: one('UNCONFIRMED', 'CONFIRMED', 'INCORRECT', 'UNKNOWN'),
});
const message: Check = (value) =>
  messageShape(value) &&
  isRecord(value) &&
  (!('attachments' in value) ||
    (Array.isArray(value.attachments) &&
      value.attachments.length <= 5 &&
      value.attachments.every(imageAttachment))) &&
  (!('imageObservationQuestions' in value) ||
    (value.sender === 'AI' &&
      Array.isArray(value.imageObservationQuestions) &&
      value.imageObservationQuestions.length <= 15 &&
      value.imageObservationQuestions.every(imageObservation) &&
      new Set(
        value.imageObservationQuestions.map(
          (item: D.ImageObservationQuestion) => item.observationId,
        ),
      ).size === value.imageObservationQuestions.length));
const runShape = obj({
  id: isUuid,
  inquiryId: isUuid,
  inquiryVersion: positive,
  answerMessageId: isUuid,
  status: str,
  error: nullable(error),
});
const run: Check = (v) =>
  runShape(v) &&
  isRecord(v) &&
  (['FAILED', 'TIMED_OUT'].includes(String(v.status)) ? v.error !== null : v.error === null);
const ticketShape = obj({
  id: isUuid,
  inquiryId: isUuid,
  problemId: isUuid,
  workItemId: isUuid,
  ticketNumber: str,
  title: str,
  processingType: str,
  reportedSystem: system,
  responsibleSystem: system,
  assignee: user,
  state: str,
  ticketRevision: positive,
  createdAt: time,
  updatedAt: time,
});
const ticket: Check = (v) => ticketShape(v) && isRecord(v) && v.problemId === v.workItemId;
const creationShape = obj({
  workItemId: isUuid,
  problemId: isUuid,
  inquiryVersion: positive,
  outcome: one('PENDING', 'STORED', 'NOT_CREATED', 'UNKNOWN'),
  ticketId: nullable(isUuid),
  error: nullable(error),
  updatedAt: time,
});
const creation: Check = (v) =>
  creationShape(v) &&
  isRecord(v) &&
  v.workItemId === v.problemId &&
  (v.outcome === 'STORED' ? isUuid(v.ticketId) : v.ticketId === null);
const knowledgeShape = obj({
  id: isUuid,
  documentId: isUuid,
  documentVersion: positive,
  kind: one('GUIDE', 'POLICY', 'RUNBOOK', 'CASE'),
  title: str,
  excerpt: text,
  systemId: nullable(isUuid),
  appliesToSystemVersion: nullable(str),
  applicability: str,
  accessScope: one('EMPLOYEE', 'SYSTEM_STAFF'),
  origin: one('SYNTHETIC', 'DEMO_VERIFIED', 'OPERATIONAL'),
});
const knowledge: Check = (v) =>
  knowledgeShape(v) && isRecord(v) && (v.systemId !== null || v.kind === 'POLICY');
const resolution = obj({
  id: isUuid,
  inquiryId: isUuid,
  problemId: isUuid,
  ticketId: nullable(isUuid),
  source: one('GUIDE', 'WORK_RECORD'),
  sourceId: isUuid,
  sourceVersion: positive,
  requestRevision: positive,
  state: str,
  resultSummary: str,
  confirmationOrigin: one('SYNTHETIC_PRESET', 'HOPS_USER'),
  createdAt: time,
  respondedAt: nullable(time),
});
const guide = obj({
  messageId: isUuid,
  evidence: list((v) => knowledge(v) && isRecord(v) && v.accessScope === 'EMPLOYEE'),
});
const problem = obj({
  id: isUuid,
  inquiryId: isUuid,
  summary: str,
  intent: str,
  processingType: str,
  reportedSystemId: nullable(isUuid),
  reportedFeatureId: nullable(isUuid),
  state: str,
  questionCount: (v) => integer(v) && Number(v) <= 5,
  guide: nullable(guide),
  creationResult: nullable(creation),
  ticket: nullable(ticket),
  activeResolutionRequest: nullable(resolution),
});
const statement = obj({
  text: str,
  sourceMessageIds: list(isUuid),
  knowledgeEvidenceIds: list(isUuid),
});
const standardRequest = obj({
  id: isUuid,
  problemId: isUuid,
  inquiryVersion: positive,
  summary: str,
  confirmedFacts: list(statement),
  assumptions: list(statement),
  unknowns: list(str),
  responsibleSystemCandidateId: nullable(isUuid),
  responsibilityEvidenceIds: list(isUuid),
  details: obj({
    taskOrScreen: nullable(str),
    requestedAccess: nullable(str),
    businessPurpose: nullable(str),
    expectedBehavior: nullable(str),
    actualBehavior: nullable(str),
    occurredAt: nullable(str),
    currentWorkflow: nullable(str),
    desiredChange: nullable(str),
    acceptanceCriteria: list(obj({ text: str, confirmation: str, confirmedBy: nullable(isUuid) })),
  }),
  knowledge: list(knowledge),
});
const history = obj({
  id: isUuid,
  previousSystemId: nullable(isUuid),
  previousAssigneeId: nullable(isUuid),
  systemId: isUuid,
  assignee: user,
  reason: str,
  evidenceMessageIds: list(isUuid),
  evidenceNote: nullable(str),
  actorId: nullable(isUuid),
  createdAt: time,
});
const management: Check = (v) =>
  ticket(v) && obj({ currentPrimary: user, assignmentHistory: list(history) })(v);
const workRecord = obj({
  id: isUuid,
  ticketId: isUuid,
  kind: str,
  action: str,
  verification: nullable(str),
  confirmedCause: nullable(str),
  appliesToSystemVersion: nullable(str),
  author: user,
  createdAt: time,
});
const generationShape = obj({
  id: isUuid,
  workRecordId: isUuid,
  state: str,
  caseId: nullable(isUuid),
  error: nullable(error),
});
const generation: Check = (v) =>
  generationShape(v) && isRecord(v) && (v.state !== 'SUCCEEDED' || v.caseId !== null);
const detail: Check = (v) =>
  management(v) &&
  obj({
    standardRequest,
    workRecords: list(workRecord),
    activeResolutionRequest: nullable(resolution),
    caseGeneration: nullable(generation),
  })(v);
const inquiry = obj({
  id: isUuid,
  inquiryNumber: str,
  inquiryVersion: positive,
  status: str,
  problems: list(problem),
  currentRun: nullable(run),
  pendingTargetMessageIds: list(isUuid),
  resultRevision: positive,
  createdAt: time,
  updatedAt: time,
});
const summary = obj({
  id: isUuid,
  inquiryNumber: str,
  title: str,
  status: str,
  problemCount: integer,
  ticketCount: integer,
  pendingConfirmationCount: integer,
  createdAt: time,
  updatedAt: time,
});
const page = (item: Check): Check =>
  obj({
    items: list(item),
    page: integer,
    size: (v) => positive(v) && Number(v) <= 100,
    totalElements: integer,
  });
const caseSummary = obj({
  id: isUuid,
  title: str,
  systemId: isUuid,
  latestCaseVersion: positive,
  latestState: str,
  publishedVersions: list(positive),
  generationState: str,
  updatedAt: time,
});
const caseVersion = obj({
  id: isUuid,
  caseVersion: positive,
  state: str,
  ticketId: isUuid,
  problemId: isUuid,
  workRecordId: isUuid,
  resolutionRequestId: isUuid,
  systemId: isUuid,
  caseType: str,
  problemStatement: str,
  confirmedCause: nullable(str),
  title: str,
  action: str,
  verification: str,
  applicability: str,
  limitations: list(str),
  appliesToSystemVersion: nullable(str),
  accessScope: one('EMPLOYEE', 'SYSTEM_STAFF'),
  origin: str,
  confirmationOrigin: str,
  sourceMessageIds: list(isUuid),
  createdAt: time,
});
const approval = obj({ caseId: isUuid, caseVersion: positive, approvedBy: user, approvedAt: time });
const indexResult = obj({
  jobId: isUuid,
  caseId: isUuid,
  caseVersion: positive,
  store: one('VECTOR', 'NEO4J'),
  state: str,
  attempt: positive,
  error: nullable(error),
  updatedAt: time,
});
const caseDetail = obj({
  version: caseVersion,
  approval: nullable(approval),
  indexes: list(indexResult),
  publication: obj({ caseId: isUuid, caseVersion: positive, state: str, canSearch: bool }),
});

function checked(value: unknown, check: Check): unknown {
  if (!check(value)) throw new Error('신규 API 응답 형식이 계약과 달라요.');
  return value;
}
// 아래 단언은 필수 키·자료형·null과 계약 조건을 검사한 외부 JSON 경계에 한정한다.
export const readUser = (v: unknown) => checked(v, currentUser) as D.CurrentUser;
export const readLogin = (v: unknown) =>
  checked(v, obj({ accessToken: str, expiresAt: time, user: currentUser })) as D.LoginResponse;
export const readSystems = (v: unknown) => checked(v, list(system)) as D.SupportedSystem[];
export const readReceipt = (v: unknown) => checked(v, receipt) as D.CommandReceipt;
export const readInquiry = (v: unknown) => checked(v, inquiry) as D.InquiryResult;
export const readInquiries = (v: unknown) => checked(v, page(summary)) as D.Page<D.InquirySummary>;
export const readMessages = (v: unknown) =>
  checked(
    v,
    obj({ items: list(message), nextBeforeSequenceNo: nullable(positive) }),
  ) as D.MessagePage;
export const readRun = (v: unknown) => checked(v, run) as D.RunSummary;
export const readTickets = (v: unknown) => checked(v, page(ticket)) as D.Page<D.TicketSummary>;
export const readTicket = (v: unknown) => checked(v, detail) as D.TicketDetail;
export const readManagementTicket = (v: unknown) =>
  checked(v, management) as D.TicketManagementDetail;
export const readRelated = (v: unknown) =>
  checked(v, obj({ items: list(related), nextCursor: nullable(str) })) as D.RelatedMessagePage;
export const readCreation = (v: unknown) => checked(v, creation) as D.CreationResult;
export const readPrimary = (v: unknown) =>
  checked(v, obj({ systemId: isUuid, primaryUser: user })) as D.PrimaryAssignment;
export const readResolution = (v: unknown) => checked(v, resolution) as D.ResolutionRequest;
export const readGeneration = (v: unknown) => checked(v, generation) as D.CaseGeneration;
export const readCases = (v: unknown) => checked(v, page(caseSummary)) as D.Page<D.CaseSummary>;
export const readCase = (v: unknown) => checked(v, caseDetail) as D.CaseDetail;
