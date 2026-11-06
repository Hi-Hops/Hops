"""한 run의 State 자료형과 역할 호출 입출력 봉투. 에이전트와 Supervisor가 함께 쓴다.

이 모듈은 자료형만 정의하고 supervisor·agents를 불러오지 않는다. 상태 전이와 검증은
supervisor 패키지가 담당한다.
"""

from collections.abc import Mapping
from dataclasses import dataclass
from typing import Literal, TypedDict

from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.roles import AuthorizedKnowledgeRef, RoleResult
from hops_ai.contracts.wire import (
    AiError,
    AiResult,
    AiRunRequest,
    CaseProposal,
    GuideProposal,
    KnowledgeEvidence,
    ProblemProposal,
    QuestionProposal,
    TicketExecution,
    TicketProposal,
)

type RunPhase = Literal[
    "QUEUED", "RUNNING", "FINALIZING", "SUCCEEDED", "FAILED", "TIMED_OUT", "SUPERSEDED"
]


class Usage(TypedDict):
    """실행기·도구 wrapper만 증가시키는 누적 사용량."""

    calls: int
    inputTokens: int
    outputTokens: int
    toolCalls: int
    retries: int


class Cancellation(TypedDict):
    """서버 취소 경로가 기록할 취소 여부와 안전한 사유."""

    cancelled: bool
    reason: str | None


class SupervisorState(TypedDict):
    """계약 S3의 내부 State. 역할은 결과만 반환하고 실행기가 새 State를 병합한다."""

    input: AiRunRequest
    phase: RunPhase
    activeRole: AgentRole | Literal["SUPERVISOR"] | None
    nextAction: (
        Literal["CALL_ROLE", "ASK_USER", "PROVIDE_GUIDE", "PROPOSE_TICKETS", "FINISH", "REPLY"]
        | None
    )
    # REPLY로 끝나는 대화 응답의 종류. 다른 행동에서는 None이다.
    replyKind: Literal["GREETING", "CLARIFY"] | None
    problems: dict[str, ProblemProposal]
    questions: dict[tuple[str, str], QuestionProposal]
    evidence: dict[str, KnowledgeEvidence]
    guide: GuideProposal | None
    answer: str | None
    ticketProposals: dict[str, TicketProposal]
    ticketCreationResults: dict[str, TicketExecution]
    caseProposal: CaseProposal | None
    roleResults: dict[str, RoleResult]
    pendingCalls: set[str]
    usage: Usage
    eventSeq: int
    answerChunkSeq: int
    cancellation: Cancellation
    error: AiError | None
    finalResult: AiResult | None


@dataclass(frozen=True)
class RoleRequest:
    """전문 역할에 전달하는 실행기 발급 호출 ID와 독립적인 작업 스냅샷.

    역할은 invocation_id를 결과의 invocationId로 그대로 돌려준다. 이 ID를 모델에게
    새로 만들게 하지 않는다. state는 깊게 복사한 값이므로 내부 목록을 수정하더라도
    Supervisor가 보유한 입력·제안·사용량에는 영향을 주지 않는다.
    """

    invocation_id: str
    state: SupervisorState


@dataclass(frozen=True)
class RoleResponse:
    """신뢰된 역할 어댑터가 결과와 함께 제공하는 검증 자료.

    result만 모델의 구조화 출력에서 구성한다. Knowledge 어댑터는 현재 Spring
    권한과 원문·청크 digest를 확인한 뒤 authorized_refs와 verified_evidence를
    채운다. 후자는 모델이 다시 작성한 발췌문이 아니라 검증을 마친 원본 근거다.
    병합 검사는 모델이 반환한 근거가 이 객체와 전체 필드에서 일치하는지 대조한다.
    문서 전체 digest를 짧은 발췌문의 해시와 직접 비교하지 않는다.
    allowed_entity_ids는 Curator 어댑터가 검증한 기존 관계 대상이다.
    모델 JSON에서 이러한 검증 자료를 받아 채우면 안 된다.
    단순한 Inquiry·Ticket 결과에는 이 봉투 없이 해당 Result를 바로 반환해도 된다.
    """

    result: RoleResult
    authorized_refs: tuple[AuthorizedKnowledgeRef, ...] = ()
    allowed_entity_ids: Mapping[str, set[str]] | None = None
    verified_evidence: tuple[KnowledgeEvidence, ...] = ()
    verified_ticket_results: tuple[TicketExecution, ...] = ()
