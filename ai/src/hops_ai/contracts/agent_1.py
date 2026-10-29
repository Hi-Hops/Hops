"""에이전트 공통 입출력 계약 agent-1의 Python 타입. 원본은 contracts/agents/README.md다.

Supervisor와 4개 역할이 주고받는 내부 계약이며 서비스 간 계약(api/schemas.py)과는 별도다.
LangGraph 상태나 FastAPI 객체를 이 계약에 노출하지 않는다.
"""

from typing import Annotated, Literal

from pydantic import Field, TypeAdapter, model_validator

from hops_ai.contracts.types import (
    Category,
    ContractModel,
    Count,
    Flag,
    IsoDateTime,
    NonEmptyText,
    QuestionCount,
    Ref,
    Text,
    Uuid,
    Version,
)
from hops_ai.contracts.types import Evidence as BaseEvidence
from hops_ai.contracts.types import TicketProposal as TicketProposal

CONTRACT_VERSION = "agent-1"

AgentRole = Literal["INTAKE", "KNOWLEDGE", "DIAGNOSIS", "TICKET"]
ResultStatus = Literal["OK", "ERROR", "TIMED_OUT", "CANCELLED"]
Sender = Literal["USER", "ASSISTANT"]
# 오류 코드는 계약 리뷰 전 제안이다. 서비스 간 계약의 ErrorCode와는 어댑터에서 변환한다.
AgentErrorCode = Literal[
    "INVALID_INPUT",
    "INVALID_OUTPUT",
    "MODEL_UNAVAILABLE",
    "MODEL_TIMEOUT",
    "RETRIEVAL_UNAVAILABLE",
    "TOOL_FAILURE",
    "ACCESS_DENIED",
    "DEADLINE_EXCEEDED",
    "BUDGET_EXCEEDED",
    "SUPERSEDED",
    "INTERNAL_ERROR",
]
NextAction = Literal["ASK_USER", "SEARCH", "DIAGNOSE", "PROPOSE_TICKETS", "OUT_OF_SCOPE"]
ResolutionAnswer = Literal["SOLVED", "NOT_SOLVED", "UNCLEAR"]
KnowledgeMode = Literal["ANSWER", "SUPPORT_DIAGNOSIS"]
Sufficiency = Literal["SUFFICIENT", "INSUFFICIENT"]
SuggestedNext = Literal["GUIDE_USER", "ASK_USER", "DIAGNOSE", "HANDOFF"]
Disposition = Literal["GUIDE_USER", "HANDOFF", "NEED_INFO", "UNDETERMINED"]


# ---------------------------------------------------------------- 공통 데이터 객체


class Evidence(BaseEvidence):
    """agent-1 근거. TOOL 근거는 조회 시각(observedAt)이 필수다."""

    @model_validator(mode="after")
    def _tool_evidence_needs_observed_at(self) -> "Evidence":
        if self.kind == "TOOL" and self.observedAt is None:
            raise ValueError("TOOL 근거는 observedAt이 필수다")
        return self


class Message(ContractModel):
    messageId: Uuid
    revision: Version
    sender: Sender
    content: Text


class Problem(ContractModel):
    ref: Ref
    summary: Text
    category: Category | None
    questionCount: QuestionCount


class WorkItem(ContractModel):
    ref: Ref
    problemRef: Ref
    workScope: Text
    status: Text
    ticketId: Uuid | None


class Department(ContractModel):
    id: Uuid
    code: NonEmptyText
    name: NonEmptyText
    responsibilities: list[Text]


class Actor(ContractModel):
    userId: Uuid
    departmentId: Uuid
    roles: list[Text]


class AgentContext(ContractModel):
    """Runtime이 만든다. 모델에게 수정 권한을 주지 않는다."""

    actor: Actor
    accessScopeRef: NonEmptyText
    deadlineAt: IsoDateTime
    allowedTools: list[Text]


# ---------------------------------------------------------------- 역할별 입력(payload)


class IntakePayload(ContractModel):
    messages: list[Message]
    existingProblems: list[Problem]
    resolutionTargets: list[Ref]


class KnowledgePayload(ContractModel):
    problem: Problem
    query: Text
    messages: list[Message]
    knownEvidence: list[Evidence]
    mode: KnowledgeMode


class DiagnosisPayload(ContractModel):
    problem: Problem
    messages: list[Message]
    knownEvidence: list[Evidence]
    targetServiceIds: list[Text]


# ---------------------------------------------------------------- 역할별 결과(data)


class IntakeProblem(Problem):
    nextAction: NextAction
    question: Text | None
    evidenceIds: list[Text]

    @model_validator(mode="after")
    def _ask_user_needs_a_question(self) -> "IntakeProblem":
        if self.nextAction == "ASK_USER" and not (self.question and self.question.strip()):
            raise ValueError("nextAction이 ASK_USER이면 question이 비어 있으면 안 된다")
        return self


class ResolutionSignal(ContractModel):
    targetRef: Ref
    answer: ResolutionAnswer
    evidenceIds: list[Text]


class IntakeData(ContractModel):
    replyDraft: Text
    problems: list[IntakeProblem]
    resolutionSignals: list[ResolutionSignal]


class KnowledgeData(ContractModel):
    problemRef: Ref
    answerDraft: Text | None
    sufficiency: Sufficiency
    evidenceIds: list[Text]
    missingInformation: list[Text]
    suggestedNext: SuggestedNext


class Finding(ContractModel):
    summary: Text
    evidenceIds: list[Text]


class DiagnosisData(ContractModel):
    problemRef: Ref
    findings: list[Finding]
    causeCandidates: list[Finding]
    uncertainties: list[Text]
    disposition: Disposition
    guidanceDraft: Text | None
    question: Text | None


class TicketPayload(ContractModel):
    problems: list[Problem]
    evidence: list[Evidence]
    diagnoses: list[DiagnosisData]
    departments: list[Department]
    existingWorkItems: list[WorkItem]
    csReviewProblemRefs: list[Ref]


class TicketData(ContractModel):
    ticketProposals: list[TicketProposal]
    unresolvedProblemRefs: list[Ref]


# ---------------------------------------------------------------- 요청과 결과


class AgentRequestBase(ContractModel):
    contractVersion: Literal["agent-1"]
    runId: Uuid
    invocationId: Uuid
    inquiryId: Uuid
    inquiryVersion: Version
    role: AgentRole
    context: AgentContext
    payload: ContractModel


class IntakeRequest(AgentRequestBase):
    role: Literal["INTAKE"]
    payload: IntakePayload


class KnowledgeRequest(AgentRequestBase):
    role: Literal["KNOWLEDGE"]
    payload: KnowledgePayload


class DiagnosisRequest(AgentRequestBase):
    role: Literal["DIAGNOSIS"]
    payload: DiagnosisPayload


class TicketRequest(AgentRequestBase):
    role: Literal["TICKET"]
    payload: TicketPayload


AgentRequest = Annotated[
    IntakeRequest | KnowledgeRequest | DiagnosisRequest | TicketRequest,
    Field(discriminator="role"),
]


class AgentError(ContractModel):
    code: AgentErrorCode
    message: Text
    retryable: Flag


class AgentMeta(ContractModel):
    """측정값이다. 모델이 생성하지 않는다. 모델을 쓰지 않았으면 modelId·promptVersion은 null이다."""

    durationMs: Count
    modelId: Text | None
    promptVersion: Text | None


class AgentResultBase(ContractModel):
    """OK는 역할 실행 성공을 뜻한다. 업무 해결·티켓 저장·배정 성공이 아니다."""

    contractVersion: Literal["agent-1"]
    runId: Uuid
    invocationId: Uuid
    inquiryId: Uuid
    inquiryVersion: Version
    role: AgentRole
    status: ResultStatus
    data: ContractModel | None
    error: AgentError | None
    evidence: list[Evidence]
    meta: AgentMeta

    @model_validator(mode="after")
    def _status_matches_data_and_error(self) -> "AgentResultBase":
        if self.status == "OK":
            if self.data is None or self.error is not None:
                raise ValueError("status가 OK이면 data가 있고 error는 null이어야 한다")
        elif self.data is not None or self.error is None:
            raise ValueError("status가 OK가 아니면 data는 null이고 error가 있어야 한다")
        return self


class IntakeResult(AgentResultBase):
    role: Literal["INTAKE"]
    data: IntakeData | None


class KnowledgeResult(AgentResultBase):
    role: Literal["KNOWLEDGE"]
    data: KnowledgeData | None


class DiagnosisResult(AgentResultBase):
    role: Literal["DIAGNOSIS"]
    data: DiagnosisData | None


class TicketResult(AgentResultBase):
    role: Literal["TICKET"]
    data: TicketData | None


AgentResult = Annotated[
    IntakeResult | KnowledgeResult | DiagnosisResult | TicketResult,
    Field(discriminator="role"),
]

agent_request_adapter: TypeAdapter[AgentRequest] = TypeAdapter(AgentRequest)
agent_result_adapter: TypeAdapter[AgentResult] = TypeAdapter(AgentResult)
