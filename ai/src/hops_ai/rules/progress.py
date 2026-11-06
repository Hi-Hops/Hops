"""문제별 진행 상태와 검색 근거·출처 범위를 State에서 계산한다. 저장 상태를 새로 확정하지 않는다.

에이전트와 Supervisor가 함께 쓰므로 어느 쪽 패키지도 불러오지 않는다. State는 contracts.state의
자료형만 사용한다.
"""

from typing import Literal, TypedDict

from hops_ai.contracts.roles import KnowledgeResult
from hops_ai.contracts.state import SupervisorState
from hops_ai.contracts.types import QUESTION_LIMIT
from hops_ai.contracts.wire import AiRunRequest, KnowledgeEvidence
from hops_ai.harness.errors import OutputRejected
from hops_ai.rules.assessment import answerable


def get_problem_knowledge(
    state: "SupervisorState", problem_ref: str
) -> dict[str, KnowledgeEvidence]:
    """서버의 해당 문제 스냅샷과 검증·병합된 문제별 검색 결과만 허용한다."""
    ids = {
        item.id
        for problem in state["input"].problems
        if problem.id == problem_ref and problem.standardRequest is not None
        for item in problem.standardRequest.knowledge
    }
    for result in state["roleResults"].values():
        if (
            isinstance(result, KnowledgeResult)
            and result.problemRef == problem_ref
            and result.search.state == "FOUND"
        ):
            ids.update(item.id for item in result.search.evidence)
    return {key: item for key, item in state["evidence"].items() if key in ids}


def problems_without_guide(state: "SupervisorState") -> frozenset[str]:
    """Knowledge가 오류 없이 검색했지만 직원 안내를 만들 수 없었던 문제의 problemRef 집합.

    검색 결과가 없거나(EMPTY), 결과는 있지만 직원에게 공개할 가이드가 없어 안내가 만들어지지
    않은 경우(FOUND이고 guide가 없음)다. 검색 오류(ERROR)는 포함하지 않는다. 오류를 근거 없음으로
    바꾸지 않는다. 검색한 문제가 기록되지 않은 결과는 어느 문제에도 적용하지 않는다.

    판정 모드(문제별 행동 경로)에서는 안내가 있어도 판정이 SUFFICIENT가 아니거나 안내가 판정이
    인용한 근거로 만들어지지 않았으면 "안내를 만들지 못한" 문제다. 그 문제를 담당자 검토로 넘기는
    것이 허용되는 경우이며, 이때 최종 응답에는 쓰지 않을 안내를 싣지 않는다(supervisor.decisions).
    """
    if state.get("judgment"):
        searched = {
            result.problemRef
            for result in state["roleResults"].values()
            if isinstance(result, KnowledgeResult)
            and result.problemRef is not None
            and result.search.state != "ERROR"
        }
        return frozenset(ref for ref in searched if not answerable(state, ref))
    return frozenset(
        result.problemRef
        for result in state["roleResults"].values()
        if isinstance(result, KnowledgeResult)
        and result.problemRef is not None
        and result.search.state != "ERROR"
        and result.guide is None
    )


def searched_queries(state: "SupervisorState") -> dict[str, set[str | None]]:
    """문제별로 이번 실행에서 이미 검색한 질의의 지문. 지문이 없는 결과는 None으로 센다.

    같은 문제를 같은 질의로 다시 검색해도 같은 결과이므로 반복을 피하는 데 쓴다.
    """
    searched: dict[str, set[str | None]] = {}
    for result in state["roleResults"].values():
        if isinstance(result, KnowledgeResult) and result.problemRef is not None:
            searched.setdefault(result.problemRef, set()).add(result.queryDigest)
    return searched


# 문제별 행동 경로가 고른 종료 행동이 만드는 문제의 결과.
DECISION_OUTCOMES: dict[str, str] = {
    "ANSWER": "PROVIDE_GUIDE",
    "ASK": "ASK_USER",
    "TICKET": "PROPOSE_TICKET",
    "HOLD": "WAITING_SYSTEM",
    "CLOSE": "OUT_OF_SCOPE",
}


class ProblemProgress(TypedDict):
    """문제별 준비 상태의 읽기 전용 투영. 업무 DB 상태를 새로 확정하지 않는다."""

    problemRef: str
    outcome: (
        Literal[
            "ASK_USER",
            "PROVIDE_GUIDE",
            "PROPOSE_TICKET",
            "EXISTING_WORK",
            "EXISTING_STATE",
            "OUT_OF_SCOPE",
            "WAITING_SYSTEM",
        ]
        | None
    )
    existingWorkOutcome: Literal["PENDING", "STORED", "UNKNOWN"] | None
    originalState: str | None
    remainingQuestions: int
    unknownCount: int
    availableDetails: list[str]


def get_problem_progress(state: "SupervisorState") -> list[ProblemProgress]:
    """이번 실행 대상마다 준비된 결과와 판단에 필요한 정보의 유무를 계산한다.

    Args:
        state: 입력 스냅샷과 병합된 제안. State에 별도 준비 상태를 저장하지 않고
            호출할 때마다 계산하므로 전문 결과와 준비 상태가 어긋나지 않는다.

    Returns:
        명시된 대상 문제와 이번 실행에서 발견한 new: 문제의 준비 상태 목록.
        problemIds가 비어 있으면 현재 문제 전체를 대상으로 본다. outcome=None은
        아직 질문·안내·티켓 또는 안내 가능한 기존 결과가 준비되지 않았다는 뜻이다.
        미확인 항목 수와 값이 있는 상세 필드 이름만 전달하며 내부 지식 원문은 싣지 않는다.

    시스템 미확인 대기는 서버가 이미 전달한 WAITING_SYSTEM이거나 질문 한도를
    소진했고 보고 시스템을 확인하지 못한 경우다. 원인 같은 unknowns가 남았다는
    이유만으로 조사 티켓을 막지 않는다. 기존 업무의 NOT_CREATED는 완료 근거가 아니다.
    """
    request = state["input"]
    originals = {problem.id: problem for problem in request.problems}
    targets = set(request.problemIds) if request.problemIds else set(state["problems"])
    targets.update(ref for ref in state["problems"] if ref.startswith("new:"))
    if not targets <= state["problems"].keys():
        raise OutputRejected("실행 대상 문제가 현재 State에 없다")

    questions = {question.problemRef for question in state["questions"].values()}
    work_items = {item.problemId: item for item in request.existingWorkItems}
    progress: list[ProblemProgress] = []
    for ref, problem in state["problems"].items():
        if ref not in targets:
            continue
        original = originals.get(ref)
        original_state = original.state if original else None
        question_count = original.questionCount if original else 0
        work = work_items.get(ref)
        work_outcome = work.outcome if work and work.outcome != "NOT_CREATED" else None
        outcome = None
        decided = state.get("decisions", {}).get(ref)
        # 판정 모드에서는 질문·안내·티켓이 있다는 사실이 결과가 아니다. 검색이 안내를 만들어 둬도
        # 판정이 불충분하면 보내지 않으므로, 결과는 고른 종료 행동만 정한다(서버 결과는 그대로).
        artifacts_decide = not state.get("judgment")
        if decided is not None:
            # 문제별 행동 경로: 모델과 코드가 고른 종료 행동이 결과를 정한다.
            outcome = DECISION_OUTCOMES[decided]
        elif artifacts_decide and ref in questions:
            outcome = "ASK_USER"
        elif artifacts_decide and state["guide"] is not None and state["guide"].problemRef == ref:
            outcome = "PROVIDE_GUIDE"
        elif artifacts_decide and ref in state["ticketProposals"]:
            outcome = "PROPOSE_TICKET"
        elif work_outcome is not None:
            outcome = "EXISTING_WORK"
        elif (
            problem.intent == "OUT_OF_SCOPE"
            or problem.processingType == "OUT_OF_SCOPE"
            or original_state == "OUT_OF_SCOPE"
        ):
            outcome = "OUT_OF_SCOPE"
        elif original_state in ("IN_SERVICE", "WAITING_CONFIRMATION", "RESOLVED"):
            # 서버가 제공한 기존 상태를 안내할 수 있을 뿐 새 해결·배정을 확정하지 않는다.
            outcome = "EXISTING_STATE"
        elif problem.reportedSystemCandidateId is None and (
            question_count >= QUESTION_LIMIT or original_state == "WAITING_SYSTEM"
        ):
            outcome = "WAITING_SYSTEM"

        progress.append(
            ProblemProgress(
                problemRef=ref,
                outcome=outcome,
                existingWorkOutcome=work_outcome,
                originalState=original_state,
                remainingQuestions=QUESTION_LIMIT - question_count,
                unknownCount=len(problem.unknowns),
                availableDetails=[
                    name
                    for name, value in problem.details.model_dump().items()
                    if value is not None and value != []
                ],
            )
        )
    return progress


def get_problem_message_ids(state: "SupervisorState", problem_ref: str) -> set[str]:
    """대상 문제에서 출처로 사용할 수 있는 서버 제공 메시지 ID를 계산한다.

    Args:
        state: 메시지와 표준 요청의 관계를 서버가 확정한 입력 스냅샷.
        problem_ref: 출처를 사용할 기존 문제 UUID 또는 신규 new: 참조.

    기존 문제에는 서버가 해당 문제에 연결한 현재 메시지만 사용한다. 실행 입력에서
    생략된 과거 메시지는 그 문제의 표준 요청이 보존한 출처 ID만 허용한다. 현재
    메시지가 있으면 현재 problemIds가 우선이므로 과거 스냅샷으로 연결을 우회하지 못한다.

    신규 문제는 원문에서 독립 요청을 분리해야 하므로 미연결 원문과 이번 실행 대상에
    속한 원문을 허용한다. 참고용 비대상 문제의 원문과 과거 출처 ID를 새 문제로 복사하지
    않는다. 실제 내용이 그 문제를 설명하는지는 Inquiry와 Spring의 결과 적용에서 검증한다.

    Raises:
        OutputRejected: 입력에 없는 기존 문제를 지정한 경우.

    이 함수는 원문을 재조회하거나 State를 변경하지 않는다. 확인 사실·추정과 티켓의
    책임 근거가 같은 메시지 연결 기준을 사용하도록 허용 ID 계산을 공통으로 제공한다.
    """
    request = state["input"]
    current_messages = {message.id: message for message in request.messages}
    if problem_ref.startswith("new:"):
        targets = set(request.problemIds) or {problem.id for problem in request.problems}
        message_ids = {
            message.id
            for message in request.messages
            if not message.problemIds or set(message.problemIds) <= targets
        }
    else:
        original = next(
            (problem for problem in request.problems if problem.id == problem_ref), None
        )
        if original is None:
            raise OutputRejected("입력에 없는 기존 문제의 출처를 검사할 수 없다")
        message_ids = {
            message.id for message in request.messages if problem_ref in message.problemIds
        }
        if original.standardRequest:
            for statement in (
                *original.standardRequest.confirmedFacts,
                *original.standardRequest.assumptions,
            ):
                message_ids.update(
                    source_id
                    for source_id in statement.sourceMessageIds
                    if source_id not in current_messages
                )
    return message_ids


def validate_evidence_scope(request: AiRunRequest, evidence: KnowledgeEvidence) -> None:
    """전달된 접근 범위와 근거의 시스템·공개 범위가 맞는지 확인한다."""
    access = request.accessContext
    if evidence.accessScope not in access.allowedDocumentScopes:
        raise OutputRejected("허용되지 않은 자료 범위다")
    if access.purpose == "EMPLOYEE_HELP" and evidence.accessScope != "EMPLOYEE":
        raise OutputRejected("직원 상담에는 직원 공개 근거만 사용할 수 있다")
    if evidence.systemId is not None and evidence.systemId not in access.systemIds:
        raise OutputRejected("근거 시스템이 접근 범위에 없다")
