// 생성 파일. 수정은 contract.schema.json에서 한다. Mock 계약 초안.
// 프론트와 Node는 동일한 파일을 사용한다. 런타임 검증은 별도다.
export type Uuid = string;

export type IsoDateTime = string;

export type UserContent = string;

export type Category = "ACCOUNT_AUTH" | "PERMISSION" | "NETWORK_ACCESS" | "SOFTWARE" | "BUSINESS_SERVICE_ERROR" | "OTHER";

export type Role = "BIZ_ADMIN" | "AGENT_OPS_ADMIN";

export type InquiryStatus = "IN_PROGRESS" | "WAITING_USER" | "RESOLVED" | "CLOSED_UNCONFIRMED" | "CLOSED";

export type ProcessingStatus = "WAITING" | "IN_PROGRESS" | "WAITING_CONFIRM" | "RESOLVED" | "CLOSED_UNCONFIRMED";

export type ProblemStatus = string;

export type RoutingStatus = string;

export type RunStatus = string;

export type ErrorCode = string;

export type Effect = string;

export type Department = {
  id: Uuid;
  code: string;
  name: string;
  isRoutable: boolean;
};

export type CurrentUser = {
  id: Uuid;
  employeeNo: string;
  name: string;
  department: Department;
  roles: Array<Role>;
};

export type LoginRequest = {
  loginId: string;
  password: string;
};

export type LoginResponse = {
  accessToken: string;
  expiresAt: IsoDateTime;
  user: CurrentUser;
};

export type ApiErrorBody = {
  code: ErrorCode;
  message: string;
  requestId: Uuid;
  retryable: boolean;
};

export type MessageRevision = {
  revisionNo: number;
  content: UserContent;
  createdAt: IsoDateTime;
};

export type ChatMessage = {
  id: Uuid;
  inquiryId: Uuid;
  revision: number;
  sequenceNo: number;
  sender: "USER" | "AI";
  content: string;
  createdAt: IsoDateTime;
  revisions: Array<MessageRevision>;
};

export type Problem = {
  id: Uuid;
  summary: string;
  category: "ACCOUNT_AUTH" | "PERMISSION" | "NETWORK_ACCESS" | "SOFTWARE" | "BUSINESS_SERVICE_ERROR" | "OTHER" | null;
  status: ProblemStatus;
  questionCount: number;
};

export type RunSummary = {
  id: Uuid;
  inquiryVersion: number;
  status: RunStatus;
  error: ApiErrorBody | null;
};

export type TicketCard = {
  id: Uuid;
  inquiryId: Uuid;
  problemId: Uuid;
  workItemId: Uuid;
  ticketNumber: string;
  title: string;
  department: Department;
  processingStatus: ProcessingStatus;
  routingStatus: RoutingStatus;
  createdAt: IsoDateTime;
};

export type AssignmentHistory = {
  department: Department;
  assignedAt: IsoDateTime;
  endedAt: string | null;
};

export type TicketDetail = {
  id: Uuid;
  inquiryId: Uuid;
  problemId: Uuid;
  workItemId: Uuid;
  ticketNumber: string;
  title: string;
  department: Department;
  processingStatus: ProcessingStatus;
  routingStatus: RoutingStatus;
  createdAt: IsoDateTime;
  description: string;
  diagnosisSummary: string | null;
  assignmentHistory: Array<AssignmentHistory>;
};

export type CreationFailure = {
  workItemId: Uuid;
  problemId: Uuid;
  status: string;
  errorCode: ErrorCode;
};

export type InquiryResult = {
  id: Uuid;
  version: number;
  status: InquiryStatus;
  problems: Array<Problem>;
  tickets: Array<TicketCard>;
  creationFailures: Array<CreationFailure>;
  currentRun: RunSummary | null;
};

export type InquirySummary = {
  id: Uuid;
  title: string;
  status: InquiryStatus;
  createdAt: IsoDateTime;
};

export type TicketPage = {
  items: Array<TicketCard>;
  page: number;
  size: number;
  totalElements: number;
};

export type InquiryPage = {
  items: Array<InquirySummary>;
  page: number;
  size: number;
  totalElements: number;
};

export type MessagePage = {
  items: Array<ChatMessage>;
  nextAfterSequenceNo: number | null;
};

export type TicketListQuery = {
  page: number;
  size: number;
  processingStatus: "WAITING" | "IN_PROGRESS" | "WAITING_CONFIRM" | "RESOLVED" | "CLOSED_UNCONFIRMED" | null;
};

export type InquiryListQuery = {
  page: number;
  size: number;
};

export type MessageListQuery = {
  afterSequenceNo: number;
  size: number;
};

export type InquiryCreateCommand = {
  requestId: Uuid;
  content: UserContent;
};

export type MessageSendCommand = {
  requestId: Uuid;
  inquiryId: Uuid;
  expectedInquiryVersion: number;
  content: UserContent;
};

export type MessageReviseCommand = {
  requestId: Uuid;
  inquiryId: Uuid;
  expectedInquiryVersion: number;
  content: UserContent;
  messageId: Uuid;
};

export type InquiryTarget = {
  type: "INQUIRY";
  id: Uuid;
};

export type DepartmentTicketsTarget = {
  type: "DEPARTMENT_TICKETS";
  id: Uuid;
};

export type TicketTarget = {
  type: "TICKET";
  id: Uuid;
};

export type SubscriptionTarget = InquiryTarget | DepartmentTicketsTarget | TicketTarget;

export type SubscriptionCommand = {
  requestId: Uuid;
  target: SubscriptionTarget;
};

export type StoredAck = {
  ok: true;
  requestId: Uuid;
  inquiryId: Uuid;
  inquiryVersion: number;
  messageId: Uuid;
  revision: number;
  runId: string | null;
  effect: Effect;
};

export type ErrorAck = {
  ok: false;
  requestId: Uuid;
  error: ApiErrorBody;
};

export type SubscriptionAccepted = {
  ok: true;
  requestId: Uuid;
  target: SubscriptionTarget;
};

export type CommandAck = StoredAck | ErrorAck;

export type SubscriptionAck = SubscriptionAccepted | ErrorAck;

export type AgentProgressPayload = {
  stepNo: number;
  stepType: string;
  displaySummary: string;
  status: string;
};

export type AnswerDeltaPayload = {
  messageId: Uuid;
  seq: number;
  text: string;
};

export type AgentProgressEvent = {
  eventId: Uuid;
  inquiryId: Uuid;
  inquiryVersion: number;
  runId: Uuid;
  payload: AgentProgressPayload;
};

export type AnswerDeltaEvent = {
  eventId: Uuid;
  inquiryId: Uuid;
  inquiryVersion: number;
  runId: Uuid;
  payload: AnswerDeltaPayload;
};

export type MessageStoredEvent = {
  eventId: Uuid;
  inquiryId: Uuid;
  inquiryVersion: number;
  messageId: Uuid;
  requestId: string | null;
};

export type StateChangedEvent = {
  eventId: Uuid;
  target: SubscriptionTarget;
  inquiryVersion: number | null;
};

export type Ref = string;

export type ActorContext = {
  userId: Uuid;
  departmentId: Uuid;
  roles: Array<Role>;
  accessScopeRef: string;
};

export type AiDepartment = {
  id: Uuid;
  code: string;
  name: string;
  isRoutable: boolean;
  responsibilities: Array<string>;
};

export type Evidence = {
  id: string;
  kind: "MESSAGE" | "DOCUMENT" | "TOOL";
  sourceId: string;
  sourceVersion: string;
  summary: string;
  observedAt: string | null;
};

export type WorkItem = {
  ref: Ref;
  problemRef: Ref;
  workScope: string;
  status: string;
  ticketId: string | null;
};

export type AiRequest = {
  schemaVersion: "hops-mock-1";
  runId: Uuid;
  inquiryId: Uuid;
  inquiryVersion: number;
  actor: ActorContext;
  deadlineAt: IsoDateTime;
  messages: Array<ChatMessage>;
  existingProblems: Array<Problem>;
  existingWorkItems: Array<WorkItem>;
  departments: Array<AiDepartment>;
  evidence: Array<Evidence>;
};

export type AiProblem = {
  ref: Ref;
  summary: string;
  category: "ACCOUNT_AUTH" | "PERMISSION" | "NETWORK_ACCESS" | "SOFTWARE" | "BUSINESS_SERVICE_ERROR" | "OTHER" | null;
  questionCount: number;
  action: "ASK_USER" | "PROPOSE_TICKETS" | "OUT_OF_SCOPE";
  question: string | null;
  evidenceIds: Array<string>;
};

export type TicketProposal = {
  ref: Ref;
  problemRef: Ref;
  workScope: string;
  title: string;
  description: string;
  category: Category;
  departmentCode: string;
  purpose: "INCIDENT" | "CS_REVIEW";
  diagnosisSummary: string;
  handoffReason: string;
  uncertainty: string | null;
  evidenceIds: Array<string>;
};

export type AiSuccess = {
  schemaVersion: "hops-mock-1";
  runId: Uuid;
  inquiryId: Uuid;
  inquiryVersion: number;
  status: "OK";
  answer: string;
  problems: Array<AiProblem>;
  ticketProposals: Array<TicketProposal>;
  error: null;
};

export type AiFailure = {
  schemaVersion: "hops-mock-1";
  runId: Uuid;
  inquiryId: Uuid;
  inquiryVersion: number;
  status: "ERROR" | "TIMED_OUT" | "CANCELLED";
  error: ApiErrorBody;
};

export type AiResult = AiSuccess | AiFailure;

export type AiAccepted = {
  schemaVersion: "hops-mock-1";
  runId: Uuid;
  inquiryId: Uuid;
  inquiryVersion: number;
  status: "ACCEPTED";
};

export type AiProgress = {
  schemaVersion: "hops-mock-1";
  runId: Uuid;
  inquiryId: Uuid;
  inquiryVersion: number;
  payload: AgentProgressPayload;
};

export type AiDelta = {
  schemaVersion: "hops-mock-1";
  runId: Uuid;
  inquiryId: Uuid;
  inquiryVersion: number;
  payload: AnswerDeltaPayload;
};

