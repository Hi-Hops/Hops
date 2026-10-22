# 생성 파일. Mock 계약 초안. dataclass 자체는 JSON 런타임 검증기가 아니다.
from __future__ import annotations
from dataclasses import dataclass
from typing import Literal, Optional, Union

Uuid = str

IsoDateTime = str

UserContent = str

Category = Literal['ACCOUNT_AUTH', 'PERMISSION', 'NETWORK_ACCESS', 'SOFTWARE', 'BUSINESS_SERVICE_ERROR', 'OTHER']

Role = Literal['BIZ_ADMIN', 'AGENT_OPS_ADMIN']

InquiryStatus = Literal['IN_PROGRESS', 'WAITING_USER', 'RESOLVED', 'CLOSED_UNCONFIRMED', 'CLOSED']

ProcessingStatus = Literal['WAITING', 'IN_PROGRESS', 'WAITING_CONFIRM', 'RESOLVED', 'CLOSED_UNCONFIRMED']

ProblemStatus = str

RoutingStatus = str

RunStatus = str

ErrorCode = str

Effect = str

@dataclass(frozen=True)
class Department:
    id: Uuid
    code: str
    name: str
    isRoutable: bool

@dataclass(frozen=True)
class CurrentUser:
    id: Uuid
    employeeNo: str
    name: str
    department: Department
    roles: list[Role]

@dataclass(frozen=True)
class LoginRequest:
    loginId: str
    password: str

@dataclass(frozen=True)
class LoginResponse:
    accessToken: str
    expiresAt: IsoDateTime
    user: CurrentUser

@dataclass(frozen=True)
class ApiErrorBody:
    code: ErrorCode
    message: str
    requestId: Uuid
    retryable: bool

@dataclass(frozen=True)
class MessageRevision:
    revisionNo: int
    content: UserContent
    createdAt: IsoDateTime

@dataclass(frozen=True)
class ChatMessage:
    id: Uuid
    inquiryId: Uuid
    revision: int
    sequenceNo: int
    sender: Literal['USER', 'AI']
    content: str
    createdAt: IsoDateTime
    revisions: list[MessageRevision]

@dataclass(frozen=True)
class Problem:
    id: Uuid
    summary: str
    category: Literal['ACCOUNT_AUTH', 'PERMISSION', 'NETWORK_ACCESS', 'SOFTWARE', 'BUSINESS_SERVICE_ERROR', 'OTHER', None]
    status: ProblemStatus
    questionCount: int

@dataclass(frozen=True)
class RunSummary:
    id: Uuid
    inquiryVersion: int
    status: RunStatus
    error: Optional['ApiErrorBody']

@dataclass(frozen=True)
class TicketCard:
    id: Uuid
    inquiryId: Uuid
    problemId: Uuid
    workItemId: Uuid
    ticketNumber: str
    title: str
    department: Department
    processingStatus: ProcessingStatus
    routingStatus: RoutingStatus
    createdAt: IsoDateTime

@dataclass(frozen=True)
class AssignmentHistory:
    department: Department
    assignedAt: IsoDateTime
    endedAt: Optional[str]

@dataclass(frozen=True)
class TicketDetail:
    id: Uuid
    inquiryId: Uuid
    problemId: Uuid
    workItemId: Uuid
    ticketNumber: str
    title: str
    department: Department
    processingStatus: ProcessingStatus
    routingStatus: RoutingStatus
    createdAt: IsoDateTime
    description: str
    diagnosisSummary: Optional[str]
    assignmentHistory: list[AssignmentHistory]

@dataclass(frozen=True)
class CreationFailure:
    workItemId: Uuid
    problemId: Uuid
    status: str
    errorCode: ErrorCode

@dataclass(frozen=True)
class InquiryResult:
    id: Uuid
    version: int
    status: InquiryStatus
    problems: list[Problem]
    tickets: list[TicketCard]
    creationFailures: list[CreationFailure]
    currentRun: Optional['RunSummary']

@dataclass(frozen=True)
class InquirySummary:
    id: Uuid
    title: str
    status: InquiryStatus
    createdAt: IsoDateTime

@dataclass(frozen=True)
class TicketPage:
    items: list[TicketCard]
    page: int
    size: int
    totalElements: int

@dataclass(frozen=True)
class InquiryPage:
    items: list[InquirySummary]
    page: int
    size: int
    totalElements: int

@dataclass(frozen=True)
class MessagePage:
    items: list[ChatMessage]
    nextAfterSequenceNo: Optional[int]

@dataclass(frozen=True)
class TicketListQuery:
    page: int
    size: int
    processingStatus: Literal['WAITING', 'IN_PROGRESS', 'WAITING_CONFIRM', 'RESOLVED', 'CLOSED_UNCONFIRMED', None]

@dataclass(frozen=True)
class InquiryListQuery:
    page: int
    size: int

@dataclass(frozen=True)
class MessageListQuery:
    afterSequenceNo: int
    size: int

@dataclass(frozen=True)
class InquiryCreateCommand:
    requestId: Uuid
    content: UserContent

@dataclass(frozen=True)
class MessageSendCommand:
    requestId: Uuid
    inquiryId: Uuid
    expectedInquiryVersion: int
    content: UserContent

@dataclass(frozen=True)
class MessageReviseCommand:
    requestId: Uuid
    inquiryId: Uuid
    expectedInquiryVersion: int
    content: UserContent
    messageId: Uuid

@dataclass(frozen=True)
class InquiryTarget:
    type: Literal['INQUIRY']
    id: Uuid

@dataclass(frozen=True)
class DepartmentTicketsTarget:
    type: Literal['DEPARTMENT_TICKETS']
    id: Uuid

@dataclass(frozen=True)
class TicketTarget:
    type: Literal['TICKET']
    id: Uuid

SubscriptionTarget = Union['InquiryTarget', 'DepartmentTicketsTarget', 'TicketTarget']

@dataclass(frozen=True)
class SubscriptionCommand:
    requestId: Uuid
    target: SubscriptionTarget

@dataclass(frozen=True)
class StoredAck:
    ok: Literal[True]
    requestId: Uuid
    inquiryId: Uuid
    inquiryVersion: int
    messageId: Uuid
    revision: int
    runId: Optional[str]
    effect: Effect

@dataclass(frozen=True)
class ErrorAck:
    ok: Literal[False]
    requestId: Uuid
    error: ApiErrorBody

@dataclass(frozen=True)
class SubscriptionAccepted:
    ok: Literal[True]
    requestId: Uuid
    target: SubscriptionTarget

CommandAck = Union['StoredAck', 'ErrorAck']

SubscriptionAck = Union['SubscriptionAccepted', 'ErrorAck']

@dataclass(frozen=True)
class AgentProgressPayload:
    stepNo: int
    stepType: str
    displaySummary: str
    status: str

@dataclass(frozen=True)
class AnswerDeltaPayload:
    messageId: Uuid
    seq: int
    text: str

@dataclass(frozen=True)
class AgentProgressEvent:
    eventId: Uuid
    inquiryId: Uuid
    inquiryVersion: int
    runId: Uuid
    payload: AgentProgressPayload

@dataclass(frozen=True)
class AnswerDeltaEvent:
    eventId: Uuid
    inquiryId: Uuid
    inquiryVersion: int
    runId: Uuid
    payload: AnswerDeltaPayload

@dataclass(frozen=True)
class MessageStoredEvent:
    eventId: Uuid
    inquiryId: Uuid
    inquiryVersion: int
    messageId: Uuid
    requestId: Optional[str]

@dataclass(frozen=True)
class StateChangedEvent:
    eventId: Uuid
    target: SubscriptionTarget
    inquiryVersion: Optional[int]

Ref = str

@dataclass(frozen=True)
class ActorContext:
    userId: Uuid
    departmentId: Uuid
    roles: list[Role]
    accessScopeRef: str

@dataclass(frozen=True)
class AiDepartment:
    id: Uuid
    code: str
    name: str
    isRoutable: bool
    responsibilities: list[str]

@dataclass(frozen=True)
class Evidence:
    id: str
    kind: Literal['MESSAGE', 'DOCUMENT', 'TOOL']
    sourceId: str
    sourceVersion: str
    summary: str
    observedAt: Optional[str]

@dataclass(frozen=True)
class WorkItem:
    ref: Ref
    problemRef: Ref
    workScope: str
    status: str
    ticketId: Optional[str]

@dataclass(frozen=True)
class AiRequest:
    schemaVersion: Literal['hops-mock-1']
    runId: Uuid
    inquiryId: Uuid
    inquiryVersion: int
    actor: ActorContext
    deadlineAt: IsoDateTime
    messages: list[ChatMessage]
    existingProblems: list[Problem]
    existingWorkItems: list[WorkItem]
    departments: list[AiDepartment]
    evidence: list[Evidence]

@dataclass(frozen=True)
class AiProblem:
    ref: Ref
    summary: str
    category: Literal['ACCOUNT_AUTH', 'PERMISSION', 'NETWORK_ACCESS', 'SOFTWARE', 'BUSINESS_SERVICE_ERROR', 'OTHER', None]
    questionCount: int
    action: Literal['ASK_USER', 'PROPOSE_TICKETS', 'OUT_OF_SCOPE']
    question: Optional[str]
    evidenceIds: list[str]

@dataclass(frozen=True)
class TicketProposal:
    ref: Ref
    problemRef: Ref
    workScope: str
    title: str
    description: str
    category: Category
    departmentCode: str
    purpose: Literal['INCIDENT', 'CS_REVIEW']
    diagnosisSummary: str
    handoffReason: str
    uncertainty: Optional[str]
    evidenceIds: list[str]

@dataclass(frozen=True)
class AiSuccess:
    schemaVersion: Literal['hops-mock-1']
    runId: Uuid
    inquiryId: Uuid
    inquiryVersion: int
    status: Literal['OK']
    answer: str
    problems: list[AiProblem]
    ticketProposals: list[TicketProposal]
    error: None

@dataclass(frozen=True)
class AiFailure:
    schemaVersion: Literal['hops-mock-1']
    runId: Uuid
    inquiryId: Uuid
    inquiryVersion: int
    status: Literal['ERROR', 'TIMED_OUT', 'CANCELLED']
    error: ApiErrorBody

AiResult = Union['AiSuccess', 'AiFailure']

@dataclass(frozen=True)
class AiAccepted:
    schemaVersion: Literal['hops-mock-1']
    runId: Uuid
    inquiryId: Uuid
    inquiryVersion: int
    status: Literal['ACCEPTED']

@dataclass(frozen=True)
class AiProgress:
    schemaVersion: Literal['hops-mock-1']
    runId: Uuid
    inquiryId: Uuid
    inquiryVersion: int
    payload: AgentProgressPayload

@dataclass(frozen=True)
class AiDelta:
    schemaVersion: Literal['hops-mock-1']
    runId: Uuid
    inquiryId: Uuid
    inquiryVersion: int
    payload: AnswerDeltaPayload

