"""Spring과 AI 서버가 주고받는 외부 전송 자료형."""

import re
from datetime import datetime
from typing import Annotated, Literal
from urllib.parse import urlsplit
from uuid import UUID

from pydantic import (
    AfterValidator,
    BaseModel,
    ConfigDict,
    Field,
    StrictBool,
    StrictInt,
    StrictStr,
    model_validator,
)


class WireModel(BaseModel):
    # 계약에 정의되지 않은 필드가 들어오면 조용히 무시하지 않고 거부한다.
    # 한 번 검증한 외부 요청은 실행 중에 변경하지 않는다.
    model_config = ConfigDict(extra="forbid", frozen=True)


# 양의 정수만 허용한다.
# StrictInt를 사용하면 문자열 "3"이나 불리언 True를 정수로 받아들이지 않는다.
PositiveLimit = Annotated[StrictInt, Field(gt=0)]

# 재시도 횟수는 0도 허용한다.
RetryLimit = Annotated[StrictInt, Field(ge=0)]


class ExecutionLimits(WireModel):
    # 한 실행에서 허용할 모델 호출 횟수
    maxModelCalls: PositiveLimit

    # 한 실행에서 허용할 입력 토큰 합계
    maxInputTokens: PositiveLimit

    # 한 실행에서 허용할 출력 토큰 합계
    maxOutputTokens: PositiveLimit

    # 한 실행에서 허용할 도구 호출 횟수
    maxToolCalls: PositiveLimit

    # 한 실행에서 허용할 재시도 횟수
    maxRetries: RetryLimit


def validate_wire_uuid(value: str) -> str:
    """계약에서 요구하는 소문자·하이픈 UUID 형식을 검사한다."""

    try:
        parsed = UUID(value)
    except ValueError as exc:
        raise ValueError("유효한 UUID가 아닙니다") from exc

    # UUID()는 여러 입력 형식을 받아들일 수 있다.
    # 계약에서는 소문자와 하이픈을 포함한 표준 문자열만 허용한다.
    if str(parsed) != value:
        raise ValueError("UUID는 소문자와 하이픈을 포함해야 합니다")

    return value


# UUID 문자열이 필요한 필드에서 이 타입을 재사용한다.
WireUuid = Annotated[StrictStr, AfterValidator(validate_wire_uuid)]


class AccessContext(WireModel):
    # Spring이 확인한 실행 주체의 ID
    actorId: WireUuid

    # 어떤 목적으로 이 실행을 수행하는지 나타낸다.
    purpose: Literal[
        "EMPLOYEE_HELP",
        "SYSTEM_WORK",
        "CASE_DRAFT",
    ]

    # 이번 실행에서 접근이 허용된 시스템 ID 목록
    systemIds: list[WireUuid]

    # 이번 실행에서 접근할 수 있는 문서 공개 범위
    allowedDocumentScopes: list[Literal["EMPLOYEE", "SYSTEM_STAFF"]]

    # Spring이 확인한 권한 정책 버전
    policyRevision: Annotated[StrictInt, Field(gt=0, le=2_147_483_647)]

    # 내부 판단 경계(옵션, 기본 꺼짐): Spring이 서버 설정으로만 채우는 가산 필드.
    # 담당자 전용 자료를 내부 판단 입력으로만 읽을 수 있는 범위이며
    # allowedDocumentScopes를 모두 포함한다. 없으면 기본 프로필이다.
    # 이 값은 사용자에게 닿을 수 있는 자료 범위(allowedDocumentScopes)를 넓히지 않는다.
    retrievalScopes: list[Literal["EMPLOYEE", "SYSTEM_STAFF"]] | None = None

    @model_validator(mode="after")
    def validate_retrieval_scopes(self) -> "AccessContext":
        if self.retrievalScopes is None:
            return self
        if not self.retrievalScopes or len(set(self.retrievalScopes)) != len(self.retrievalScopes):
            raise ValueError("retrievalScopes는 비어 있거나 중복될 수 없습니다")
        if not set(self.allowedDocumentScopes) <= set(self.retrievalScopes):
            raise ValueError("retrievalScopes는 allowedDocumentScopes를 모두 포함해야 합니다")
        return self


class ExistingWorkItem(WireModel):
    workItemId: WireUuid
    problemId: WireUuid

    # 문의 입력 버전은 양의 정수다.
    inquiryVersion: Annotated[
        StrictInt,
        Field(gt=0, le=2_147_483_647),
    ]

    outcome: Literal[
        "PENDING",
        "STORED",
        "NOT_CREATED",
        "UNKNOWN",
    ]

    # None은 허용하지만 기본값을 지정하지 않는다.
    # 따라서 JSON에서 ticketId 키는 반드시 보내야 한다.
    ticketId: WireUuid | None

    @model_validator(mode="after")
    def validate_ticket_id(self) -> "ExistingWorkItem":
        # 저장 완료라면 실제 ticketId가 있어야 한다.
        if self.outcome == "STORED" and self.ticketId is None:
            raise ValueError("STORED 상태에는 ticketId가 필요합니다")

        # 저장 완료가 아니면 ticketId를 전달하지 않는다.
        if self.outcome != "STORED" and self.ticketId is not None:
            raise ValueError("STORED가 아닌 상태의 ticketId는 null이어야 합니다")

        return self


# wire 타입은 내부 실행 타입과 별도로 검증한다.
_WHITESPACE = (
    "\t\n\v\f\r \u0085\u00a0\u1680\u2000\u2001\u2002\u2003\u2004"
    "\u2005\u2006\u2007\u2008\u2009\u200a\u2028\u2029\u202f\u205f\u3000\ufeff"
)


def _text(value: str) -> str:
    if not value.strip(_WHITESPACE):
        raise ValueError("공백만 있는 문자열은 허용하지 않습니다")
    return value


def _content(value: str) -> str:
    _text(value)
    if len(value) > 4000 or any(0xD800 <= ord(char) <= 0xDFFF for char in value):
        raise ValueError("본문은 surrogate 없이 1~4000 코드 포인트여야 합니다")
    return value


def _time(value: str) -> str:
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z", value):
        raise ValueError("시각은 UTC RFC 3339 Z 형식이어야 합니다")
    try:
        datetime.fromisoformat(value)
    except ValueError as exc:
        raise ValueError("유효하지 않은 시각입니다") from exc
    return value


def _problem_ref(value: str) -> str:
    if re.fullmatch(r"new:[A-Za-z0-9_-]+", value):
        return value
    return validate_wire_uuid(value)


WireText = Annotated[StrictStr, AfterValidator(_text)]
WireContent = Annotated[StrictStr, AfterValidator(_content)]
WireTime = Annotated[StrictStr, AfterValidator(_time)]
ProblemRef = Annotated[StrictStr, AfterValidator(_problem_ref)]
Version = Annotated[StrictInt, Field(gt=0, le=2_147_483_647)]
NonNegative = Annotated[StrictInt, Field(ge=0)]
QuestionCount = Annotated[StrictInt, Field(ge=0, le=5)]
Title = Annotated[WireText, Field(max_length=200)]
SystemVersion = Annotated[WireText, Field(max_length=100)]
AccessScope = Literal["EMPLOYEE", "SYSTEM_STAFF"]
RunKind = Literal["INQUIRY_ANALYSIS", "CASE_DRAFT"]
Intent = Literal[
    "HOW_TO",
    "ACCESS_REQUEST",
    "OBSERVED_SYMPTOM",
    "FEATURE_REQUEST",
    "IMPROVEMENT_REQUEST",
    "UNKNOWN",
    "OUT_OF_SCOPE",
]
ProcessingType = Literal[
    "KNOWLEDGE_QA",
    "SERVICE_REQUEST",
    "INVESTIGATION",
    "INCIDENT",
    "CHANGE_REQUEST",
    "REVIEW_REQUIRED",
    "OUT_OF_SCOPE",
]
ProblemState = Literal[
    "ANALYZING",
    "WAITING_USER",
    "WAITING_SYSTEM",
    "TICKET_PENDING",
    "IN_SERVICE",
    "WAITING_CONFIRMATION",
    "RESOLVED",
    "OUT_OF_SCOPE",
]


class SystemFeature(WireModel):
    id: WireUuid
    name: WireText


# 지원 시스템의 안정적인 코드. 어떤 시스템이 있는지는 요청이 알려 주므로 값 목록을 코드에 두지 않고
# 형식만 검사한다(대문자·숫자·밑줄). 허용 코드 집합은 서버가 보낸 시스템 목록이 정한다.
SystemCode = Annotated[StrictStr, Field(pattern=r"^[A-Z][A-Z0-9_]{1,63}$")]


class SupportedSystem(WireModel):
    id: WireUuid
    code: SystemCode
    name: WireText
    features: list[SystemFeature]


class InputMessage(WireModel):
    id: WireUuid
    revision: Version
    sequenceNo: Version
    sender: Literal["USER", "AI"]
    content: WireContent
    problemIds: list[WireUuid]
    createdAt: WireTime


class EvidenceStatement(WireModel):
    text: WireText
    sourceMessageIds: list[WireUuid]
    knowledgeEvidenceIds: list[WireUuid]


class AcceptanceCriterion(WireModel):
    text: WireText
    confirmation: Literal["PROPOSED", "CONFIRMED"]
    confirmedBy: WireUuid | None

    @model_validator(mode="after")
    def validate_confirmation(self) -> "AcceptanceCriterion":
        if (self.confirmation == "PROPOSED") != (self.confirmedBy is None):
            raise ValueError("수용 조건의 확인 상태와 확인자가 일치하지 않습니다")
        return self


class RequestDetails(WireModel):
    taskOrScreen: WireText | None
    requestedAccess: WireText | None
    businessPurpose: WireText | None
    expectedBehavior: WireText | None
    actualBehavior: WireText | None
    occurredAt: WireText | None
    currentWorkflow: WireText | None
    desiredChange: WireText | None
    acceptanceCriteria: list[AcceptanceCriterion]


class KnowledgeEvidence(WireModel):
    id: WireUuid
    documentId: WireUuid
    documentVersion: Version
    kind: Literal["GUIDE", "POLICY", "RUNBOOK", "CASE"]
    title: Title
    excerpt: WireText
    systemId: WireUuid | None
    appliesToSystemVersion: SystemVersion | None
    applicability: WireText
    accessScope: AccessScope
    origin: Literal["SYNTHETIC", "DEMO_VERIFIED", "OPERATIONAL"]

    @model_validator(mode="after")
    def validate_system(self) -> "KnowledgeEvidence":
        if self.systemId is None and self.kind != "POLICY":
            raise ValueError("공통 POLICY만 systemId가 null일 수 있습니다")
        return self


class StandardRequestBody(WireModel):
    summary: WireText
    confirmedFacts: list[EvidenceStatement]
    assumptions: list[EvidenceStatement]
    unknowns: list[WireText]
    responsibleSystemCandidateId: WireUuid | None
    responsibilityEvidenceIds: list[WireUuid]
    details: RequestDetails
    knowledge: list[KnowledgeEvidence]

    @model_validator(mode="after")
    def validate_sources(self) -> "StandardRequestBody":
        if any(
            not fact.sourceMessageIds and not fact.knowledgeEvidenceIds
            for fact in self.confirmedFacts
        ):
            raise ValueError("확인 사실에는 출처가 필요합니다")
        return self


class ResolutionContext(WireModel):
    requestId: WireUuid
    source: Literal["GUIDE", "WORK_RECORD"]
    sourceId: WireUuid
    sourceVersion: Version
    state: Literal["PENDING", "RESOLVED", "UNRESOLVED"]


class InputProblem(WireModel):
    id: WireUuid
    summary: WireText
    intent: Intent
    processingType: ProcessingType
    reportedSystemId: WireUuid | None
    reportedFeatureId: WireUuid | None
    state: ProblemState
    questionCount: QuestionCount
    # 이 문제에 이미 물은 항목(질문 묶음 requestedFields의 합집합). Spring이 저장한 질문 이력이다.
    # 이전 생산자는 보내지 않으므로 생략하면 빈 목록이다. 이미 물은 항목은 답을 못 얻어도
    # 다시 묻지 않는다.
    askedFields: list[WireText] = Field(default_factory=list)
    standardRequest: StandardRequestBody | None
    resolutionContext: ResolutionContext | None


class RelatedWorkRecord(WireModel):
    """같은 티켓의 다른 처리 기록. 사례의 원 기록이 아니라 과정을 이해하는 맥락이다.

    진행·실패 시도도 포함하지만 사례의 조치·검증·원인으로 쓰지 않는다.
    실패 시도는 사례를 만들지 않는다.
    """

    id: WireUuid
    kind: Literal["PROGRESS", "FAILED_ATTEMPT", "VERIFIED_RESOLUTION", "IMPLEMENTED_VERIFIED"]
    action: Annotated[WireText, Field(max_length=4000)]
    verification: Annotated[WireText, Field(max_length=4000)] | None
    confirmedCause: Annotated[WireText, Field(max_length=4000)] | None
    appliesToSystemVersion: SystemVersion | None
    createdAt: WireTime


class ResolutionHistoryEntry(WireModel):
    """이 문제에 문의자에게 보낸 해결 확인 요청 한 건과 그 상태. 안내·처리 기록 양쪽을 포함한다."""

    requestId: WireUuid
    source: Literal["GUIDE", "WORK_RECORD"]
    sourceId: WireUuid
    state: Literal["PENDING", "RESOLVED", "UNRESOLVED"]
    requestedAt: WireTime
    respondedAt: WireTime | None


class LinkCandidate(WireModel):
    """사례를 연결할 수 있는 기존 노드. Spring이 승인된 것만 골라 보낸다.

    SYMPTOM은 `ADDRESSES`, CHECK_ITEM은 `USES_CHECK`의 대상이다. 이 목록 밖의 노드를 연결하거나
    새 노드를 만들 수 없다. 원인(Cause)은 연결 대상이 아니다.
    """

    id: WireUuid
    kind: Literal["SYMPTOM", "CHECK_ITEM"]
    label: Annotated[WireText, Field(max_length=200)]
    systemId: WireUuid


# 맥락 입력의 개수 상한. Spring이 같은 값으로 자르며 넘으면 입력 오류다.
MAX_RELATED_RECORDS = 20
MAX_RESOLUTION_HISTORY = 20
MAX_CONSULTED_KNOWLEDGE = 10
MAX_SYMPTOM_CANDIDATES = 30
MAX_CHECK_CANDIDATES = 40


class WorkRecordContext(WireModel):
    id: WireUuid
    ticketId: WireUuid
    problemId: WireUuid
    systemId: WireUuid
    kind: Literal["VERIFIED_RESOLUTION", "IMPLEMENTED_VERIFIED"]
    action: WireText
    verification: WireText
    createdAt: WireTime
    resolutionRequestId: WireUuid
    confirmationState: Literal["PENDING", "RESOLVED", "UNRESOLVED"]
    confirmedCause: Annotated[WireText, Field(max_length=4000)] | None
    appliesToSystemVersion: SystemVersion | None
    # 문의 전체 과정(#198). 모두 가산 필드라 이전 생산자는 보내지 않으며 생략하면 빈 목록이다.
    # 같은 티켓의 다른 처리 기록(원 기록 제외, 오래된 순)
    relatedRecords: list[RelatedWorkRecord] = Field(
        default_factory=list, max_length=MAX_RELATED_RECORDS
    )
    # 이 문제의 해결 확인 이력(원 기록의 확인 요청 제외, 오래된 순)
    resolutionHistory: list[ResolutionHistoryEntry] = Field(
        default_factory=list, max_length=MAX_RESOLUTION_HISTORY
    )
    # 상담에 쓰인 근거. Spring이 현재 권한으로 다시 확인해 보낸 것만 있으며 최신 상담 순이다.
    consultedKnowledge: list[KnowledgeEvidence] = Field(
        default_factory=list, max_length=MAX_CONSULTED_KNOWLEDGE
    )
    # 사례를 연결할 수 있는 같은 시스템의 기존 증상·확인 항목(승인된 것만).
    # 비어 있으면 `link` 판단을 하지 않는다.
    linkCandidates: list[LinkCandidate] = Field(
        default_factory=list, max_length=MAX_SYMPTOM_CANDIDATES + MAX_CHECK_CANDIDATES
    )

    @model_validator(mode="after")
    def validate_context(self) -> "WorkRecordContext":
        if any(record.id == self.id for record in self.relatedRecords):
            raise ValueError("관련 처리 기록에 원 기록이 들어 있습니다")
        for name, ids in (
            ("관련 처리 기록", [record.id for record in self.relatedRecords]),
            ("해결 확인 이력", [entry.requestId for entry in self.resolutionHistory]),
            ("상담 근거", [item.id for item in self.consultedKnowledge]),
        ):
            if len(ids) != len(set(ids)):
                raise ValueError(f"{name}의 식별자가 중복됐습니다")
        if any(entry.requestId == self.resolutionRequestId for entry in self.resolutionHistory):
            raise ValueError("해결 확인 이력에 원 기록의 확인 요청이 들어 있습니다")
        candidate_ids = [item.id for item in self.linkCandidates]
        if len(candidate_ids) != len(set(candidate_ids)):
            raise ValueError("연결 후보의 식별자가 중복됐습니다")
        if any(item.systemId != self.systemId for item in self.linkCandidates):
            raise ValueError("연결 후보가 원 기록의 시스템과 다른 시스템에 속합니다")
        symptoms = sum(item.kind == "SYMPTOM" for item in self.linkCandidates)
        if (
            symptoms > MAX_SYMPTOM_CANDIDATES
            or len(self.linkCandidates) - symptoms > MAX_CHECK_CANDIDATES
        ):
            raise ValueError("연결 후보가 종류별 상한을 넘었습니다")
        return self


class AiRunRequest(WireModel):
    contractVersion: Literal["hops-system-1"]
    runId: WireUuid
    kind: RunKind
    inquiryId: WireUuid
    inquiryVersion: Version
    answerMessageId: WireUuid | None
    problemIds: list[WireUuid]
    messages: list[InputMessage]
    problems: list[InputProblem]
    systems: list[SupportedSystem]
    accessContext: AccessContext
    existingWorkItems: list[ExistingWorkItem]
    workRecord: WorkRecordContext | None
    expiresAt: WireTime
    limits: ExecutionLimits

    @model_validator(mode="after")
    def validate_kind_fields(self) -> "AiRunRequest":
        if self.kind == "INQUIRY_ANALYSIS":
            if self.answerMessageId is None or self.workRecord is not None:
                raise ValueError("상담 실행의 답변 ID 또는 처리 기록이 올바르지 않습니다")
            if self.accessContext.purpose not in ("EMPLOYEE_HELP", "SYSTEM_WORK"):
                raise ValueError("상담 실행에 맞지 않는 접근 목적입니다")
        elif (
            self.answerMessageId is not None
            or self.workRecord is None
            or self.accessContext.purpose != "CASE_DRAFT"
        ):
            raise ValueError("사례 초안 실행의 답변 ID·처리 기록·접근 목적이 올바르지 않습니다")
        return self


class AiError(WireModel):
    code: Literal[
        "INVALID_OUTPUT",
        "SEARCH_UNAVAILABLE",
        "TOOL_UNAVAILABLE",
        "MODEL_UNAVAILABLE",
        "DEADLINE_EXCEEDED",
        "BUDGET_EXCEEDED",
        "AUTH_CONTEXT_INVALID",
        "SUPERSEDED",
        "INDEX_UNAVAILABLE",
        "UPSTREAM_PROTOCOL_ERROR",
        "NOT_FOUND",
        "CONFLICT",
        "INVALID_REQUEST",
        "RATE_LIMITED",
        "SERVICE_UNAVAILABLE",
    ]
    message: WireText
    retryable: StrictBool


class ProblemProposal(WireModel):
    problemRef: ProblemRef
    summary: WireText
    intent: Intent
    processingType: ProcessingType
    reportedSystemCandidateId: WireUuid | None
    facts: list[EvidenceStatement]
    assumptions: list[EvidenceStatement]
    unknowns: list[WireText]
    details: RequestDetails

    @model_validator(mode="after")
    def validate_sources(self) -> "ProblemProposal":
        if any(not fact.sourceMessageIds and not fact.knowledgeEvidenceIds for fact in self.facts):
            raise ValueError("확인 사실에는 출처가 필요합니다")
        return self


class QuestionProposal(WireModel):
    problemRef: ProblemRef
    bundleRef: WireText
    text: WireText
    requestedFields: list[WireText]


class GuideProposal(WireModel):
    messageText: WireText
    evidenceIds: list[WireUuid]
    problemRef: ProblemRef


class TicketProposal(WireModel):
    problemRef: ProblemRef
    title: Title
    processingType: Literal["SERVICE_REQUEST", "INVESTIGATION", "CHANGE_REQUEST", "REVIEW_REQUIRED"]
    responsibleSystemCandidateId: WireUuid
    evidenceIds: list[WireUuid]
    standardRequestSnapshot: StandardRequestBody


class EntityLink(WireModel):
    relation: Literal["APPLIES_TO", "ADDRESSES", "USES_CHECK"]
    targetId: WireText
    evidenceIds: list[WireUuid]


class CaseProposal(WireModel):
    reusable: StrictBool
    title: Title | None
    action: WireText | None
    verification: WireText | None
    applicability: WireText | None
    limitations: list[WireText]
    appliesToSystemVersion: SystemVersion | None
    accessScope: AccessScope | None
    sourceMessageIds: list[WireUuid]
    entityLinks: list[EntityLink]
    problemStatement: WireText | None

    @model_validator(mode="after")
    def validate_reusability(self) -> "CaseProposal":
        required = (
            self.title,
            self.action,
            self.verification,
            self.applicability,
            self.accessScope,
            self.problemStatement,
        )
        if self.reusable and any(value is None for value in required):
            raise ValueError("재사용 가능한 사례에는 본문과 공개 범위가 필요합니다")
        if not self.reusable and (
            any(value is not None for value in required)
            or self.appliesToSystemVersion is not None
            or self.limitations
            or self.sourceMessageIds
            or self.entityLinks
        ):
            raise ValueError("재사용하지 않는 사례에는 본문이나 출처를 포함할 수 없습니다")
        return self


class SpringTicketResult(WireModel):
    """서비스 인증된 Spring 응답. 모델 출력 스키마로 사용하지 않는다."""

    contractVersion: Literal["hops-system-1"]
    runId: WireUuid
    problemRef: WireText
    workItemId: WireUuid
    problemId: WireUuid
    inquiryVersion: Version
    outcome: Literal["STORED", "NOT_CREATED", "PENDING", "UNKNOWN"]
    ticketId: WireUuid | None
    responsibleSystemId: WireUuid | None
    assigneeId: WireUuid | None
    attempt: Annotated[StrictInt, Field(ge=1, le=3)]
    maxAttempts: Annotated[StrictInt, Field(ge=1, le=3)]
    retryable: StrictBool
    errorCode: WireText | None
    updatedAt: WireTime

    @model_validator(mode="after")
    def validate_storage(self) -> "SpringTicketResult":
        if self.workItemId != self.problemId or self.attempt > self.maxAttempts:
            raise ValueError("작업 식별자 또는 시도 횟수가 다릅니다")
        ids = (self.ticketId, self.responsibleSystemId, self.assigneeId)
        if self.outcome == "STORED":
            if any(value is None for value in ids) or self.errorCode is not None or self.retryable:
                raise ValueError("저장 성공에는 실제 티켓·배정 참조가 필요합니다")
        elif any(value is not None for value in ids):
            raise ValueError("저장 미확정에는 성공 참조를 포함하지 않습니다")
        if self.retryable and not (
            self.outcome == "NOT_CREATED"
            and self.errorCode == "ASSIGNMENT_UNAVAILABLE"
            and self.attempt < self.maxAttempts
        ):
            raise ValueError("미생성 확인과 남은 시도 없이 재시도할 수 없습니다")
        return self


class TicketExecution(WireModel):
    problemRef: WireText
    result: SpringTicketResult | None
    errorCode: WireText | None


class AiResult(WireModel):
    contractVersion: Literal["hops-system-1"]
    runId: WireUuid
    kind: RunKind
    inquiryId: WireUuid
    inquiryVersion: Version
    status: Literal["SUCCEEDED", "FAILED", "TIMED_OUT", "SUPERSEDED"]
    answer: WireContent | None
    problemProposals: list[ProblemProposal]
    questions: list[QuestionProposal]
    guide: GuideProposal | None
    ticketProposals: list[TicketProposal]
    caseProposal: CaseProposal | None
    evidence: list[KnowledgeEvidence]
    error: AiError | None
    # 이전 생산자의 생략은 false다. 이 제안만으로 업무 상태를 확정하지 않는다.
    requiresFollowUp: StrictBool = False
    # 이전 생산자의 제안 전용 결과를 읽을 수 있는 가산 필드. 신규 출력에는 항상 포함한다.
    ticketCreationResults: list[TicketExecution] = Field(default_factory=list)

    @model_validator(mode="after")
    def validate_result(self) -> "AiResult":
        if self.requiresFollowUp and (
            self.kind != "INQUIRY_ANALYSIS" or self.status != "SUCCEEDED"
        ):
            raise ValueError("추가 응답 대기는 성공한 상담 결과에서만 허용합니다")
        refs = [item.problemRef for item in self.ticketCreationResults]
        if len(refs) != len(set(refs)) or any(
            item.result is not None
            and (
                item.result.runId != self.runId
                or item.result.problemRef != item.problemRef
                or item.result.inquiryVersion != self.inquiryVersion
            )
            for item in self.ticketCreationResults
        ):
            raise ValueError("티켓 생성 결과의 실행·문제·버전이 다릅니다")
        if self.kind != "INQUIRY_ANALYSIS" and self.ticketCreationResults:
            raise ValueError("사례 실행에 티켓 생성 결과를 포함하지 않습니다")
        if self.status != "SUCCEEDED":
            if (
                self.error is None
                or self.answer is not None
                or self.guide is not None
                or self.caseProposal is not None
                or self.problemProposals
                or self.questions
                or self.ticketProposals
                or self.evidence
            ):
                raise ValueError("실패 결과에는 오류만 포함하고 새 제안은 포함하지 않습니다")
        elif self.error is not None:
            raise ValueError("성공 결과에는 오류를 포함할 수 없습니다")
        elif self.kind == "INQUIRY_ANALYSIS":
            if self.answer is None or self.caseProposal is not None:
                raise ValueError("상담 성공에는 답변이 필요하고 사례 제안은 포함하지 않습니다")
            refs = [question.problemRef for question in self.questions]
            # 질문 횟수는 문제별 질문 묶음을 기준으로 서버가 집계한다. 여기서는
            # 필수 본문의 누락만 검사한다. 다른 질문 안의 같은 문구까지 세면
            # 정상적인 복합 문의가 실패하므로 부분 문자열 출현 횟수는 사용하지 않는다.
            if len(refs) != len(set(refs)) or any(
                q.text not in self.answer for q in self.questions
            ):
                raise ValueError("문제별 질문과 답변 본문이 일치하지 않습니다")
            if self.guide and (
                self.guide.messageText not in self.answer or self.guide.problemRef in refs
            ):
                raise ValueError("가이드와 답변 또는 질문이 일치하지 않습니다")
            if self.guide:
                # 허용된 근거의 최신 승인과 권한은 실행기·Spring이 다시 확인한다.
                evidence_by_id = {item.id: item for item in self.evidence}
                if not self.guide.evidenceIds or any(
                    ref not in evidence_by_id or evidence_by_id[ref].accessScope != "EMPLOYEE"
                    for ref in self.guide.evidenceIds
                ):
                    raise ValueError("가이드에는 직원에게 허용된 근거가 필요합니다")
            if any(
                t.problemRef in refs or (self.guide and t.problemRef == self.guide.problemRef)
                for t in self.ticketProposals
            ):
                raise ValueError("같은 문제에 질문·가이드와 티켓을 함께 제안할 수 없습니다")
            by_ref = {p.problemRef: p.processingType for p in self.problemProposals}
            if any(
                t.problemRef in by_ref and by_ref[t.problemRef] != t.processingType
                for t in self.ticketProposals
            ):
                raise ValueError("티켓과 문제의 처리 유형이 일치하지 않습니다")
        elif (
            self.caseProposal is None
            or self.answer is not None
            or self.guide is not None
            or self.problemProposals
            or self.questions
            or self.ticketProposals
        ):
            raise ValueError("사례 초안 성공에는 사례 제안이 필요합니다")
        return self


class AiAccepted(WireModel):
    contractVersion: Literal["hops-system-1"]
    runId: WireUuid
    status: Literal["QUEUED"]
    eventsPath: WireText

    @model_validator(mode="after")
    def validate_events_path(self) -> "AiAccepted":
        path = urlsplit(self.eventsPath)
        if (
            not self.eventsPath.startswith("/")
            or self.eventsPath.startswith("//")
            or path.scheme
            or path.netloc
            or path.query
            or path.fragment
            or "\\" in self.eventsPath
        ):
            raise ValueError("eventsPath는 고정 origin 안의 상대 경로여야 합니다")
        return self


class AiRunSnapshot(WireModel):
    contractVersion: Literal["hops-system-1"]
    runId: WireUuid
    status: Literal[
        "QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "TIMED_OUT", "SUPERSEDED", "UNKNOWN"
    ]
    lastSeq: NonNegative
    result: AiResult | None
    error: AiError | None

    @model_validator(mode="after")
    def validate_snapshot(self) -> "AiRunSnapshot":
        if self.status in ("QUEUED", "RUNNING", "UNKNOWN"):
            if self.result is not None:
                raise ValueError("진행 중이거나 미확인인 실행에는 최종 결과가 없습니다")
        elif (
            self.result is None
            or self.result.status != self.status
            or self.result.runId != self.runId
        ):
            raise ValueError("종료 상태와 같은 실행의 최종 결과가 필요합니다")
        if self.status == "SUCCEEDED" and self.error is not None:
            raise ValueError("성공 상태에는 오류가 없습니다")
        if self.status in ("FAILED", "TIMED_OUT", "SUPERSEDED") and (
            self.error is None or (self.result is not None and self.error != self.result.error)
        ):
            raise ValueError("종료 오류는 결과의 오류와 일치해야 합니다")
        return self


class ProgressPayload(WireModel):
    role: Literal["SUPERVISOR", "INQUIRY", "KNOWLEDGE", "TICKET", "CURATOR"]
    displaySummary: WireText


class DeltaPayload(WireModel):
    messageId: WireUuid
    chunkSeq: Version
    text: WireText


class AiEvent(WireModel):
    contractVersion: Literal["hops-system-1"]
    runId: WireUuid
    seq: Version
    eventType: Literal["progress", "answer.delta", "result"]
    payload: ProgressPayload | DeltaPayload | AiResult

    @model_validator(mode="after")
    def validate_payload(self) -> "AiEvent":
        expected = {"progress": ProgressPayload, "answer.delta": DeltaPayload, "result": AiResult}
        if not isinstance(self.payload, expected[self.eventType]):
            raise ValueError("이벤트 유형과 payload가 일치하지 않습니다")
        if isinstance(self.payload, AiResult) and self.payload.runId != self.runId:
            raise ValueError("이벤트와 결과의 runId가 일치하지 않습니다")
        return self
