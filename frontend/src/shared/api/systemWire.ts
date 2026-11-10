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
const message = obj({
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

const finite: Check = (v) => typeof v === 'number' && Number.isFinite(v);
const nonnegative: Check = (v) => finite(v) && Number(v) >= 0;
const similarity: Check = (v) => finite(v) && Number(v) >= -1 && Number(v) <= 1.000001;
const recommendationShape = obj({
  query: str,
  score: nonnegative,
  scoreMethod: one('WEIGHTED_RRF'),
  keywordRank: nullable(positive),
  semanticRank: nullable(positive),
  keywordScore: nullable(nonnegative),
  semanticSimilarity: nullable(similarity),
  matchedKeywords: list(str),
  reasons: (v) => list(str)(v) && Array.isArray(v) && v.length > 0,
  comparisons: list(
    obj({
      key: str,
      label: str,
      status: one('MATCHED', 'MISMATCHED', 'UNKNOWN'),
      currentEvidence: list(str),
      caseEvidence: str,
    }),
  ),
});
const recommendation: Check = (v) => {
  if (!recommendationShape(v) || !isRecord(v)) return false;
  if ((v.keywordRank === null) !== (v.keywordScore === null)) return false;
  if ((v.semanticRank === null) !== (v.semanticSimilarity === null)) return false;
  if (v.keywordRank === null && v.semanticRank === null) return false;
  if (v.keywordRank === null && Array.isArray(v.matchedKeywords) && v.matchedKeywords.length)
    return false;
  const keyword = v.keywordRank === null ? 0 : 0.8 / (10 + Number(v.keywordRank));
  const semantic = v.semanticRank === null ? 0 : 1 / (10 + Number(v.semanticRank));
  return Math.abs(Number(v.score) - keyword - semantic) < 0.000001;
};
export const readRecommendedCases = (v: unknown) =>
  checked(
    v,
    list(
      (item) =>
        knowledge(item) &&
        isRecord(item) &&
        item.kind === 'CASE' &&
        (item.recommendation === undefined ||
          item.recommendation === null ||
          recommendation(item.recommendation)),
    ),
  ) as D.RecommendedCase[];

const conditionComparison = obj({
  key: str,
  label: str,
  status: one('MATCHED', 'MISMATCHED', 'UNKNOWN'),
  currentEvidence: list(str),
  caseEvidence: str,
});
const assessment = obj({
  relevance: one('HIGH', 'MEDIUM', 'REFERENCE', 'CONFLICT'),
  family: str,
  reasons: list(str),
  conditions: list(conditionComparison),
  quotePairs: list(
    obj({
      label: str,
      status: one('MATCHED', 'MISMATCHED', 'UNKNOWN'),
      messageId: isUuid,
      currentQuote: str,
      currentStart: integer,
      currentEnd: positive,
      sourceQuote: str,
      sourceStart: integer,
      sourceEnd: positive,
    }),
  ),
  sections: list(obj({ label: str, quote: str, start: integer, end: positive })),
  nextChecks: list(str),
  matched: integer,
  unknown: integer,
  mismatched: integer,
});
const assistanceCase: Check = (v) =>
  obj({ evidence: knowledge, assessment })(v) &&
  isRecord(v) &&
  (v.sourceCase === undefined ||
    v.sourceCase === null ||
    obj({ caseNumber: str, ticketId: isUuid, ticketNumber: str, approvedAt: time })(v.sourceCase));
const assistanceMaterial: Check = (v) =>
  assistanceCase(v) &&
  isRecord(v) &&
  (v.groupedCases === undefined ||
    (Array.isArray(v.groupedCases) &&
      v.groupedCases.length <= 20 &&
      v.groupedCases.every(
        (member) => assistanceCase(member) && isRecord(member) && member.groupedCases === undefined,
      )));
export function readTicketAssistance(value: unknown): D.TicketAssistance {
  const result = checked(
    value,
    obj({
      ticketId: isUuid,
      ticketRevision: positive,
      standardRequestId: isUuid,
      query: str,
      sources: list(related),
      materials: list(assistanceMaterial),
    }),
  ) as D.TicketAssistance;
  for (const parent of result.materials) {
    const group = parent.groupedCases ?? [];
    if (
      group.length > 0 &&
      (group.length < 2 ||
        parent.evidence.kind !== 'CASE' ||
        !group.some((m) => m.evidence.id === parent.evidence.id) ||
        new Set(group.map((m) => m.evidence.id)).size !== group.length ||
        group.some(
          (m) =>
            m.evidence.kind !== 'CASE' ||
            m.evidence.systemId !== parent.evidence.systemId ||
            m.evidence.accessScope !== parent.evidence.accessScope ||
            m.evidence.appliesToSystemVersion !== parent.evidence.appliesToSystemVersion,
        ))
    )
      throw new Error('묶인 사례 범위 불일치');
  }
  for (const material of result.materials.flatMap((m) => [m, ...(m.groupedCases ?? [])])) {
    const { evidence, assessment: review } = material;
    if (evidence.recommendation && !recommendation(evidence.recommendation))
      throw new Error('검색 근거 형식 불일치');
    if (
      review.matched !== review.conditions.filter((c) => c.status === 'MATCHED').length ||
      review.unknown !== review.conditions.filter((c) => c.status === 'UNKNOWN').length ||
      review.mismatched !== review.conditions.filter((c) => c.status === 'MISMATCHED').length ||
      (review.mismatched > 0 && review.relevance !== 'CONFLICT')
    )
      throw new Error('조건 판정 불일치');
    if (review.relevance === 'HIGH' && (review.unknown > 0 || review.matched < 2))
      throw new Error('조건 확인 전 강한 추천');
    for (const pair of review.quotePairs) {
      const source = result.sources.find((s) => s.id === pair.messageId && s.sender === 'USER');
      if (
        source?.content.slice(pair.currentStart, pair.currentEnd) !== pair.currentQuote ||
        pair.currentEnd > source.content.length ||
        pair.sourceEnd > evidence.excerpt.length ||
        !review.conditions.some((c) => c.label === pair.label && c.status === pair.status) ||
        evidence.excerpt.slice(pair.sourceStart, pair.sourceEnd) !== pair.sourceQuote
      )
        throw new Error('원문 인용 구간 불일치');
    }
    for (const section of review.sections)
      if (
        section.end > evidence.excerpt.length ||
        evidence.excerpt.slice(section.start, section.end) !== section.quote
      )
        throw new Error('문서 원문 구간 불일치');
  }
  return result;
}
