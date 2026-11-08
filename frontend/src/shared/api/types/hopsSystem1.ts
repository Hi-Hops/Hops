/** /api/v2에서 사용하는 hops-system-1 외부 DTO. */
export type Uuid = string;
export type Department = { id: Uuid; code: string; name: string };
export type UserSummary = { id: Uuid; name: string; department: Department };
export type CurrentUser = UserSummary & {
  employeeNo: string;
  roles: 'BIZ_ADMIN'[];
  primarySystemIds: Uuid[];
};
export type LoginResponse = { accessToken: string; expiresAt: string; user: CurrentUser };
export type SupportedSystem = {
  id: Uuid;
  code: 'ELECTRONIC_APPROVAL' | 'COMMON_LOGIN' | 'FILE_SHARING';
  name: string;
  features: { id: Uuid; name: string }[];
};
export type ApiErrorBody = {
  code: string;
  message: string;
  requestId: Uuid;
  retryable: boolean;
  fieldErrors: { field: string; code: string }[];
};
export type ResourceRef = { type: string; id: Uuid };
export type CommandReceipt = {
  requestId: Uuid;
  operationId: Uuid;
  state: 'PENDING' | 'SUCCEEDED' | 'REJECTED' | 'UNKNOWN';
  resource: ResourceRef | null;
  error: ApiErrorBody | null;
};
export type Page<T> = { items: T[]; page: number; size: number; totalElements: number };
export type MessageRevision = { revisionNo: number; content: string; createdAt: string };
export type ImageAttachment = { id: Uuid; mediaType: 'image/png'; byteSize: number };
export type ImageAttachmentMetadata = ImageAttachment & {
  state: 'PENDING' | 'READY' | 'BOUND' | 'EXPIRED';
  createdAt: string;
  expiresAt: string | null;
  messageId: Uuid | null;
  linkRevision: number;
  problemIds: Uuid[];
  analysisRevision?: number;
  analysisProblemIds?: Uuid[];
};
export type ImageAnalysisTargetCommand = {
  requestId: Uuid;
  expectedInquiryVersion: number;
  expectedAnalysisRevision: number;
  problemIds: Uuid[];
};
export type ImageProblemLinkCommand = {
  requestId: Uuid;
  expectedInquiryVersion: number;
  expectedLinkRevision: number;
  problemIds: Uuid[];
  confirmImageDisclosure: boolean;
};
export type ImageObservationDecision = 'CONFIRMED' | 'INCORRECT' | 'UNKNOWN';
export type ImageObservationQuestion = {
  observationId: Uuid;
  observationRevision: number;
  attachmentId: Uuid;
  problemId: Uuid;
  text: string;
  state: 'UNCONFIRMED' | ImageObservationDecision;
};
export type ImageObservationResponseCommand = {
  requestId: Uuid;
  expectedInquiryVersion: number;
  responses: {
    observationId: Uuid;
    expectedObservationRevision: number;
    decision: ImageObservationDecision;
  }[];
};
export type ChatMessage = {
  id: Uuid;
  inquiryId: Uuid;
  requestId: Uuid | null;
  revision: number;
  sequenceNo: number;
  sender: 'USER' | 'AI';
  content: string;
  problemIds: Uuid[];
  revisions: MessageRevision[];
  createdAt: string;
  attachments?: ImageAttachment[];
  imageObservationQuestions?: ImageObservationQuestion[];
};
export type MessagePage = { items: ChatMessage[]; nextBeforeSequenceNo: number | null };
export type RunSummary = {
  id: Uuid;
  inquiryId: Uuid;
  inquiryVersion: number;
  answerMessageId: Uuid;
  status: string;
  error: ApiErrorBody | null;
};
export type TicketState = 'WAITING' | 'IN_PROGRESS' | 'WAITING_CONFIRMATION' | 'RESOLVED';
export type TicketSummary = {
  id: Uuid;
  inquiryId: Uuid;
  problemId: Uuid;
  workItemId: Uuid;
  ticketNumber: string;
  title: string;
  processingType: string;
  reportedSystem: SupportedSystem;
  responsibleSystem: SupportedSystem;
  assignee: UserSummary;
  state: string;
  ticketRevision: number;
  createdAt: string;
  updatedAt: string;
};
export type CreationResult = {
  workItemId: Uuid;
  problemId: Uuid;
  inquiryVersion: number;
  outcome: 'PENDING' | 'STORED' | 'NOT_CREATED' | 'UNKNOWN';
  ticketId: Uuid | null;
  error: ApiErrorBody | null;
  updatedAt: string;
};
export type KnowledgeEvidence = {
  id: Uuid;
  documentId: Uuid;
  documentVersion: number;
  kind: 'GUIDE' | 'POLICY' | 'RUNBOOK' | 'CASE';
  title: string;
  excerpt: string;
  systemId: Uuid | null;
  appliesToSystemVersion: string | null;
  applicability: string;
  accessScope: 'EMPLOYEE' | 'SYSTEM_STAFF';
  origin: string;
};
export type ResolutionRequest = {
  id: Uuid;
  inquiryId: Uuid;
  problemId: Uuid;
  ticketId: Uuid | null;
  source: string;
  sourceId: Uuid;
  sourceVersion: number;
  requestRevision: number;
  state: string;
  resultSummary: string;
  confirmationOrigin: string;
  createdAt: string;
  respondedAt: string | null;
};
export type Problem = {
  id: Uuid;
  inquiryId: Uuid;
  summary: string;
  intent: string;
  processingType: string;
  reportedSystemId: Uuid | null;
  reportedFeatureId: Uuid | null;
  state: string;
  questionCount: number;
  guide: { messageId: Uuid; evidence: KnowledgeEvidence[] } | null;
  creationResult: CreationResult | null;
  ticket: TicketSummary | null;
  activeResolutionRequest: ResolutionRequest | null;
};
export type InquiryResult = {
  id: Uuid;
  inquiryNumber: string;
  inquiryVersion: number;
  status: string;
  problems: Problem[];
  currentRun: RunSummary | null;
  pendingTargetMessageIds: Uuid[];
  resultRevision: number;
  createdAt: string;
  updatedAt: string;
};
export type InquirySummary = {
  id: Uuid;
  inquiryNumber: string;
  title: string;
  status: string;
  problemCount: number;
  ticketCount: number;
  pendingConfirmationCount: number;
  createdAt: string;
  updatedAt: string;
};
export type AssignmentHistory = {
  id: Uuid;
  previousSystemId: Uuid | null;
  previousAssigneeId: Uuid | null;
  systemId: Uuid;
  assignee: UserSummary;
  reason: string;
  evidenceMessageIds: Uuid[];
  evidenceNote: string | null;
  actorId: Uuid | null;
  createdAt: string;
};
export type EvidenceStatement = {
  text: string;
  sourceMessageIds: Uuid[];
  knowledgeEvidenceIds: Uuid[];
};
export type StandardRequest = {
  id: Uuid;
  problemId: Uuid;
  inquiryVersion: number;
  summary: string;
  confirmedFacts: EvidenceStatement[];
  assumptions: EvidenceStatement[];
  unknowns: string[];
  responsibleSystemCandidateId: Uuid | null;
  responsibilityEvidenceIds: Uuid[];
  details: RequestDetails;
  knowledge: KnowledgeEvidence[];
};
export type RequestDetails = {
  taskOrScreen: string | null;
  requestedAccess: string | null;
  businessPurpose: string | null;
  expectedBehavior: string | null;
  actualBehavior: string | null;
  occurredAt: string | null;
  currentWorkflow: string | null;
  desiredChange: string | null;
  acceptanceCriteria: { text: string; confirmation: string; confirmedBy: Uuid | null }[];
};
export type TicketManagementDetail = TicketSummary & {
  currentPrimary: UserSummary;
  assignmentHistory: AssignmentHistory[];
};
export type TicketDetail = TicketManagementDetail & {
  standardRequest: StandardRequest;
  workRecords: WorkRecord[];
  activeResolutionRequest: ResolutionRequest | null;
  caseGeneration: CaseGeneration | null;
};
export type RelatedMessage = Pick<
  ChatMessage,
  'id' | 'revision' | 'sender' | 'content' | 'revisions' | 'createdAt'
>;
export type RelatedMessagePage = { items: RelatedMessage[]; nextCursor: string | null };
export type InquiryCreateCommand = {
  requestId: Uuid;
  content: string;
  reportedSystemId: Uuid | null;
  attachmentIds?: Uuid[];
};
export type MessageSendCommand = {
  requestId: Uuid;
  expectedInquiryVersion: number;
  content: string;
  problemIds: Uuid[];
  attachmentIds?: Uuid[];
};
export type MessageReviseCommand = {
  requestId: Uuid;
  expectedInquiryVersion: number;
  expectedMessageRevision: number;
  content: string;
};
export type SystemConfirmCommand = {
  requestId: Uuid;
  expectedInquiryVersion: number;
  systemId: Uuid;
};
export type MessageProblemLinkCommand = {
  requestId: Uuid;
  expectedInquiryVersion: number;
  problemIds: Uuid[];
  newProblem?: true;
};
export type RunRetryCommand = { requestId: Uuid; expectedInquiryVersion: number };
export type CreationRetryCommand = { requestId: Uuid; expectedInquiryVersion: number };
export type PrimaryAssignment = { systemId: Uuid; primaryUser: UserSummary };
export type WorkRecordKind =
  'PROGRESS' | 'FAILED_ATTEMPT' | 'VERIFIED_RESOLUTION' | 'IMPLEMENTED_VERIFIED';
export type WorkRecord = {
  id: Uuid;
  ticketId: Uuid;
  kind: string;
  action: string;
  verification: string | null;
  confirmedCause: string | null;
  appliesToSystemVersion: string | null;
  author: UserSummary;
  createdAt: string;
};
export type WorkRecordCommand = {
  requestId: Uuid;
  expectedTicketRevision: number;
  kind: WorkRecordKind;
  action: string;
  verification: string | null;
  confirmedCause: string | null;
  appliesToSystemVersion: string | null;
};
export type AssignmentCorrectionCommand = {
  requestId: Uuid;
  expectedTicketRevision: number;
  responsibleSystemId: Uuid;
  reason: string;
  evidenceMessageIds: Uuid[];
  evidenceNote: string | null;
};
export type ProcessingTypeCorrectionCommand = Omit<
  AssignmentCorrectionCommand,
  'responsibleSystemId'
> & { processingType: 'INVESTIGATION' | 'INCIDENT' };
export type ResolutionResponse = {
  requestId: Uuid;
  expectedRequestRevision: number;
  decision: 'RESOLVED' | 'UNRESOLVED';
};
export type CaseGeneration = {
  id: Uuid;
  workRecordId: Uuid;
  state: string;
  caseId: Uuid | null;
  error: ApiErrorBody | null;
};
export type CaseSummary = {
  id: Uuid;
  title: string;
  systemId: Uuid;
  latestCaseVersion: number;
  latestState: string;
  publishedVersions: number[];
  generationState: string;
  updatedAt: string;
};
export type CaseVersion = {
  id: Uuid;
  caseVersion: number;
  state: string;
  ticketId: Uuid;
  problemId: Uuid;
  workRecordId: Uuid;
  resolutionRequestId: Uuid;
  systemId: Uuid;
  caseType: string;
  problemStatement: string;
  confirmedCause: string | null;
  title: string;
  action: string;
  verification: string;
  applicability: string;
  limitations: string[];
  appliesToSystemVersion: string | null;
  accessScope: 'EMPLOYEE' | 'SYSTEM_STAFF';
  origin: string;
  confirmationOrigin: string;
  sourceMessageIds: Uuid[];
  createdAt: string;
};
export type CaseApproval = {
  caseId: Uuid;
  caseVersion: number;
  approvedBy: UserSummary;
  approvedAt: string;
};
export type IndexStore = 'VECTOR' | 'NEO4J';
export type IndexResult = {
  jobId: Uuid;
  caseId: Uuid;
  caseVersion: number;
  store: IndexStore;
  state: string;
  attempt: number;
  error: ApiErrorBody | null;
  updatedAt: string;
};
export type Publication = {
  caseId: Uuid;
  caseVersion: number;
  state: string;
  canSearch: boolean;
};
export type CaseDetail = {
  version: CaseVersion;
  approval: CaseApproval | null;
  indexes: IndexResult[];
  publication: Publication;
};
export type CaseEditCommand = {
  requestId: Uuid;
  expectedCaseVersion: number;
  problemStatement: string;
  title: string;
  action: string;
  verification: string;
  applicability: string;
  limitations: string[];
  appliesToSystemVersion: string | null;
  accessScope: 'EMPLOYEE' | 'SYSTEM_STAFF';
};
export type CaseApproveCommand = { requestId: Uuid; expectedCaseVersion: number };
export type IndexCommand = CaseApproveCommand & { store: IndexStore };
