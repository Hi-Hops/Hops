"""한 run의 작업 상태와 상태 전이. 업무 DB 상태를 직접 변경하지 않는다."""

from typing import Literal, TypedDict

from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.roles import RoleResult
from hops_ai.contracts.wire import (
    AiError,
    AiResult,
    AiRunRequest,
    CaseProposal,
    GuideProposal,
    InputProblem,
    KnowledgeEvidence,
    ProblemProposal,
    QuestionProposal,
    RequestDetails,
    TicketExecution,
    TicketProposal,
)
from hops_ai.harness.errors import InputRejected, OutputRejected
from hops_ai.supervisor.validation import ensure_active, validate_evidence_scope, validate_request

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
        Literal["CALL_ROLE", "ASK_USER", "PROVIDE_GUIDE", "PROPOSE_TICKETS", "FINISH"] | None
    )
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


def _problem_from_input(problem: InputProblem) -> ProblemProposal:
    """기존 문제 ID와 표준 요청을 내부 제안 형태로 옮기고 미확인 값은 채우지 않는다."""
    snapshot = problem.standardRequest
    details = (
        snapshot.details
        if snapshot
        else RequestDetails(
            taskOrScreen=None,
            requestedAccess=None,
            businessPurpose=None,
            expectedBehavior=None,
            actualBehavior=None,
            occurredAt=None,
            currentWorkflow=None,
            desiredChange=None,
            acceptanceCriteria=[],
        )
    )
    return ProblemProposal(
        problemRef=problem.id,
        summary=problem.summary,
        intent=problem.intent,
        processingType=problem.processingType,
        reportedSystemCandidateId=problem.reportedSystemId,
        facts=snapshot.confirmedFacts if snapshot else [],
        assumptions=snapshot.assumptions if snapshot else [],
        unknowns=snapshot.unknowns if snapshot else [],
        details=details,
    )


def create_initial_state(request: AiRunRequest) -> SupervisorState:
    """검증된 요청으로 독립적인 초기 State를 만든다.

    Args:
        request: Spring이 제공한 hops-system-1 입력. 인증·현재 권한 검사는 어댑터의 책임이다.

    Returns:
        QUEUED State. 기존 문제·근거는 유지하고 실행 사용량과 새 제안 목록은 비워 둔다.
        questionCount는 input 안의 서버 값으로 보존하며 여기서 증가시키지 않는다.

    Raises:
        InputRejected: 입력의 ID·대상 문제·지원 시스템 등의 관계가 맞지 않는 경우.
        OutputRejected: 기존 지식 스냅샷의 접근 범위가 입력 권한과 맞지 않는 경우.
        ValidationError: DTO의 필수 필드나 자료형이 계약과 다른 경우.
    """
    # 깊게 복사하여 호출자가 원래 요청의 목록을 수정해도 이 run의 입력은 유지한다.
    snapshot = AiRunRequest.model_validate(request.model_dump(mode="json"))
    validate_request(snapshot)
    evidence: dict[str, KnowledgeEvidence] = {}
    for problem in snapshot.problems:
        if problem.standardRequest is None:
            continue
        for item in problem.standardRequest.knowledge:
            validate_evidence_scope(snapshot, item)
            if item.id in evidence and evidence[item.id] != item:
                raise InputRejected("입력에 같은 ID의 서로 다른 근거가 있다")
            evidence[item.id] = item

    return SupervisorState(
        input=snapshot,
        phase="QUEUED",
        activeRole=None,
        nextAction=None,
        problems={problem.id: _problem_from_input(problem) for problem in snapshot.problems},
        questions={},
        evidence=evidence,
        guide=None,
        answer=None,
        ticketProposals={},
        ticketCreationResults={},
        caseProposal=None,
        roleResults={},
        pendingCalls=set(),
        usage=Usage(calls=0, inputTokens=0, outputTokens=0, toolCalls=0, retries=0),
        eventSeq=0,
        answerChunkSeq=0,
        cancellation=Cancellation(cancelled=False, reason=None),
        error=None,
        finalResult=None,
    )


def transition_phase(
    state: SupervisorState,
    next_phase: RunPhase,
    *,
    error: AiError | None = None,
) -> SupervisorState:
    """허용된 실행 상태 전이만 적용한 새 State를 반환한다.

    정상 흐름은 QUEUED → RUNNING → FINALIZING → SUCCEEDED다.
    종료 상태에서 다른 상태로 돌아가는 요청과 결과 없는 SUCCEEDED 전이는 거부한다.
    FAILED·TIMED_OUT·SUPERSEDED에는 안전한 기본 오류를 기록할 수 있다.

    Args:
        state: 현재 State. 이 함수는 전달받은 객체를 직접 수정하지 않는다.
        next_phase: 이동하려는 실행 상태.
        error: 실패 원인을 이미 분류했다면 전달할 외부 계약 오류.

    Returns:
        허용된 상태 전이를 반영한 State. 같은 상태의 재전달은 기존 객체를 반환한다.

    Raises:
        OutputRejected: 허용되지 않은 전이, 미완료 역할 호출 또는 잘못된 종료 결과.
        DeadlineExceeded / RunCancelled / BudgetExceeded: 정상 진행 조건을 만족하지 못한 경우.
    """
    if next_phase == state["phase"]:
        if error is not None and error != state["error"]:
            raise OutputRejected("이미 기록된 오류를 교체할 수 없다")
        return state

    transitions = {
        "QUEUED": {"RUNNING", "FAILED", "TIMED_OUT", "SUPERSEDED"},
        "RUNNING": {"FINALIZING", "FAILED", "TIMED_OUT", "SUPERSEDED"},
        "FINALIZING": {"SUCCEEDED", "FAILED", "TIMED_OUT", "SUPERSEDED"},
    }
    if next_phase not in transitions.get(state["phase"], set()) or (
        state["finalResult"] is not None and next_phase != "SUCCEEDED"
    ):
        raise OutputRejected("허용되지 않은 실행 상태 전이다")

    if next_phase in ("RUNNING", "FINALIZING", "SUCCEEDED"):
        ensure_active(state)
        if error is not None or state["error"] is not None:
            raise OutputRejected("오류가 있는 실행을 정상 진행할 수 없다")
        if next_phase != "RUNNING" and state["pendingCalls"]:
            raise OutputRejected("완료되지 않은 역할 호출이 있다")
        if next_phase == "SUCCEEDED" and (
            state["finalResult"] is None or state["finalResult"].status != "SUCCEEDED"
        ):
            raise OutputRejected("검증된 최종 결과 없이 성공으로 바꿀 수 없다")
        return {**state, "phase": next_phase}

    defaults = {
        "FAILED": AiError(
            code="INVALID_OUTPUT", message="실행 결과를 적용할 수 없다", retryable=False
        ),
        "TIMED_OUT": AiError(
            code="DEADLINE_EXCEEDED", message="실행 기한을 초과했다", retryable=False
        ),
        "SUPERSEDED": AiError(
            code="SUPERSEDED", message="새 입력 또는 취소로 실행이 중단됐다", retryable=False
        ),
    }
    failure = state["error"] or error or defaults[next_phase]
    if next_phase == "TIMED_OUT" and failure.code != "DEADLINE_EXCEEDED":
        raise OutputRejected("시간 초과 상태와 오류 코드가 다르다")
    if next_phase == "SUPERSEDED" and failure.code != "SUPERSEDED":
        raise OutputRejected("대체 상태와 오류 코드가 다르다")
    return {
        **state,
        "phase": next_phase,
        "error": state["error"] or failure,
        "activeRole": None,
        "pendingCalls": set(),
    }
