"""Spring과 주고받는 서비스 간 계약(wire DTO). 원본은 specs/contracts/DATA_TYPES.md다.

`hops-mock-1` 기준이며 운영 경로·서비스 인증은 아직 합의 전이다. 내부 agent-1과는 별도 계약이라
agent-1과의 변환은 api/mappers.py에서 한다.
"""

from typing import Annotated, Literal

from pydantic import Field, TypeAdapter

from hops_ai.contracts.types import (
    Category,
    ContractModel,
    Evidence,
    Flag,
    IsoDateTime,
    NonEmptyText,
    QuestionCount,
    Ref,
    Text,
    TicketProposal,
    Uuid,
    Version,
)

SCHEMA_VERSION = "hops-mock-1"

Role = Literal["BIZ_ADMIN", "AGENT_OPS_ADMIN"]
ProblemStatus = Literal[
    "ANALYZING", "WAITING_USER", "TICKET_CREATED", "CREATION_FAILED", "OUT_OF_SCOPE"
]
ProblemAction = Literal["ASK_USER", "PROPOSE_TICKETS", "OUT_OF_SCOPE"]
ErrorCode = Literal[
    "UNAUTHENTICATED",
    "ACCESS_TOKEN_EXPIRED",
    "REFRESH_TOKEN_EXPIRED",
    "REFRESH_TOKEN_REVOKED",
    "AUTH_CONTEXT_CHANGED",
    "ACCESS_DENIED",
    "INVALID_INPUT",
    "NOT_FOUND",
    "INQUIRY_VERSION_CONFLICT",
    "IDEMPOTENCY_KEY_REUSED",
    "RUN_NOT_RETRYABLE",
    "RATE_LIMITED",
    "SERVICE_UNAVAILABLE",
    "DEADLINE_EXCEEDED",
    "MODEL_UNAVAILABLE",
]


class ActorContext(ContractModel):
    """Spring이 검증한 최소 사용자 문맥. 사용자 JWT는 전달하지 않는다."""

    userId: Uuid
    departmentId: Uuid
    roles: list[Role]
    accessScopeRef: NonEmptyText


class MessageRevision(ContractModel):
    revisionNo: Version
    content: Text
    createdAt: IsoDateTime


class ChatMessage(ContractModel):
    id: Uuid
    inquiryId: Uuid
    revision: Version
    sequenceNo: Version
    sender: Literal["USER", "AI"]
    content: Text
    createdAt: IsoDateTime
    revisions: list[MessageRevision]


class Problem(ContractModel):
    """Spring에 이미 저장된 문제."""

    id: Uuid
    summary: Text
    category: Category | None
    status: ProblemStatus
    questionCount: QuestionCount


class WorkItem(ContractModel):
    ref: Ref
    problemRef: Ref
    workScope: Text
    status: Text
    ticketId: Uuid | None


class AiDepartment(ContractModel):
    id: Uuid
    code: NonEmptyText
    name: NonEmptyText
    isRoutable: Flag
    responsibilities: list[Text]


class ApiErrorBody(ContractModel):
    code: ErrorCode
    message: Text
    requestId: Uuid
    retryable: Flag


class AiRequest(ContractModel):
    schemaVersion: Literal["hops-mock-1"]
    runId: Uuid
    inquiryId: Uuid
    inquiryVersion: Version
    actor: ActorContext
    deadlineAt: IsoDateTime
    messages: list[ChatMessage]
    existingProblems: list[Problem]
    existingWorkItems: list[WorkItem]
    departments: list[AiDepartment]
    evidence: list[Evidence]


class AiProblem(ContractModel):
    """AI가 판단한 문제. ref는 기존 문제의 UUID 또는 `new:` 임시 참조다."""

    ref: Ref
    summary: Text
    category: Category | None
    questionCount: QuestionCount
    action: ProblemAction
    question: Text | None
    evidenceIds: list[Text]


class AiSuccess(ContractModel):
    """분석이 끝났다는 뜻이다. 티켓 저장·배정 성공이 아니다."""

    schemaVersion: Literal["hops-mock-1"]
    runId: Uuid
    inquiryId: Uuid
    inquiryVersion: Version
    status: Literal["OK"]
    answer: Text
    problems: list[AiProblem]
    ticketProposals: list[TicketProposal]
    error: None


class AiFailure(ContractModel):
    schemaVersion: Literal["hops-mock-1"]
    runId: Uuid
    inquiryId: Uuid
    inquiryVersion: Version
    status: Literal["ERROR", "TIMED_OUT", "CANCELLED"]
    error: ApiErrorBody


AiResult = Annotated[AiSuccess | AiFailure, Field(discriminator="status")]
ai_result_adapter: TypeAdapter[AiResult] = TypeAdapter(AiResult)


class AiAccepted(ContractModel):
    schemaVersion: Literal["hops-mock-1"]
    runId: Uuid
    inquiryId: Uuid
    inquiryVersion: Version
    status: Literal["ACCEPTED"]


class AgentProgressPayload(ContractModel):
    stepNo: Version
    stepType: Text
    displaySummary: Text
    status: Text


class AiProgress(ContractModel):
    schemaVersion: Literal["hops-mock-1"]
    runId: Uuid
    inquiryId: Uuid
    inquiryVersion: Version
    payload: AgentProgressPayload


class AnswerDeltaPayload(ContractModel):
    messageId: Uuid
    seq: Version
    text: Text


class AiDelta(ContractModel):
    schemaVersion: Literal["hops-mock-1"]
    runId: Uuid
    inquiryId: Uuid
    inquiryVersion: Version
    payload: AnswerDeltaPayload
