"""병합 함수가 함께 사용하는 작은 검증 함수. 실제 최신 권한 조회는 어댑터에서 수행한다."""

from datetime import UTC, datetime
from typing import TYPE_CHECKING, Literal, TypedDict

from hops_ai.contracts.wire import (
    AiRunRequest,
    EvidenceStatement,
    KnowledgeEvidence,
    RequestDetails,
)
from hops_ai.harness.errors import (
    BudgetExceeded,
    DeadlineExceeded,
    InputRejected,
    OutputRejected,
    RunCancelled,
)

if TYPE_CHECKING:
    from hops_ai.supervisor.state import SupervisorState


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

    시스템 미확인 대기는 서버가 이미 전달한 WAITING_SYSTEM이거나 질문 5회를
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
        if ref in questions:
            outcome = "ASK_USER"
        elif state["guide"] is not None and state["guide"].problemRef == ref:
            outcome = "PROVIDE_GUIDE"
        elif ref in state["ticketProposals"]:
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
            question_count >= 5 or original_state == "WAITING_SYSTEM"
        ):
            outcome = "WAITING_SYSTEM"

        progress.append(
            ProblemProgress(
                problemRef=ref,
                outcome=outcome,
                existingWorkOutcome=work_outcome,
                originalState=original_state,
                remainingQuestions=5 - question_count,
                unknownCount=len(problem.unknowns),
                availableDetails=[
                    name
                    for name, value in problem.details.model_dump().items()
                    if value is not None and value != []
                ],
            )
        )
    return progress


def validate_completion(state: "SupervisorState") -> None:
    """실행 종류별 최소 종료 조건을 검사하고 미처리 대상의 조기 종료를 막는다.

    상담은 모든 대상 문제에 질문·안내·티켓 제안 또는 안내 가능한 기존 결과와
    범위/시스템 대기 안내가 있어야 한다. 질문을 반환하는 것은 이번 run의 종료이며
    문제 해결 완료를 뜻하지 않는다. CASE_DRAFT의 reusable=false도 유효한 완료다.

    이 검사는 결과의 존재를 확인한다. 출처·권한·질문 횟수·제안 충돌 검증은 병합과
    최종화에서 함께 수행하며, 문자열이나 search EMPTY만으로 완료를 인정하지 않는다.
    실패·취소 결과를 만들 때에는 이 성공 조건을 적용하지 않는다.

    Raises:
        OutputRejected: 사례 결과가 없거나, 상담 대상이 없거나, 미처리 문제가 남은 경우.
    """
    if state["input"].kind == "CASE_DRAFT":
        if state["caseProposal"] is None:
            raise OutputRejected("사례 제안이 준비되기 전에 종료할 수 없다")
        return
    progress = get_problem_progress(state)
    if not progress or any(item["outcome"] is None for item in progress):
        raise OutputRejected("모든 대상 문제에 결과 또는 대기 안내가 준비돼야 종료할 수 있다")


def validate_request(request: AiRunRequest) -> None:
    """입력 목록의 중복과 문제·시스템·기존 작업의 연결 관계를 확인한다."""
    groups = [
        request.problemIds,
        request.accessContext.systemIds,
        request.accessContext.allowedDocumentScopes,
        [item.id for item in request.problems],
        [item.id for item in request.messages],
        [item.sequenceNo for item in request.messages],
        [item.id for item in request.systems],
        [item.code for item in request.systems],
        [item.workItemId for item in request.existingWorkItems],
        [feature.id for system in request.systems for feature in system.features],
    ]
    if any(len(group) != len(set(group)) for group in groups):
        raise InputRejected("실행 입력의 식별자 또는 순번이 중복됐다")
    problems = {item.id for item in request.problems}
    systems = {item.id for item in request.systems}
    if (
        not set(request.problemIds) <= problems
        or not set(request.accessContext.systemIds) <= systems
    ):
        raise InputRejected("입력의 문제 또는 접근 시스템 참조가 유효하지 않다")
    if (
        request.accessContext.purpose == "EMPLOYEE_HELP"
        and "SYSTEM_STAFF" in request.accessContext.allowedDocumentScopes
    ):
        raise InputRejected("직원 상담에 담당자 내부 자료 범위를 포함할 수 없다")
    for message in request.messages:
        if len(message.problemIds) != len(set(message.problemIds)):
            raise InputRejected("메시지의 문제 참조가 중복됐다")
        if not set(message.problemIds) <= problems:
            raise InputRejected("메시지의 문제 참조가 입력에 없다")
    for problem in request.problems:
        if problem.reportedSystemId is not None and problem.reportedSystemId not in systems:
            raise InputRejected("보고 시스템이 지원 시스템에 없다")
        features = {
            feature.id
            for system in request.systems
            if system.id == problem.reportedSystemId
            for feature in system.features
        }
        if problem.reportedFeatureId is not None and problem.reportedFeatureId not in features:
            raise InputRejected("보고 기능이 해당 시스템에 없다")
    for work in request.existingWorkItems:
        if (
            work.workItemId != work.problemId
            or work.problemId not in problems
            or work.inquiryVersion > request.inquiryVersion
        ):
            raise InputRejected("기존 업무가 다른 문제나 미래 입력 버전을 참조한다")
    if request.workRecord is not None:
        record = request.workRecord
        if (
            request.problemIds != [record.problemId]
            or record.problemId not in problems
            or record.systemId not in systems
        ):
            raise InputRejected("사례 원 기록과 대상 문제·시스템이 다르다")
        for message in request.messages:
            if message.problemIds != [record.problemId]:
                raise InputRejected("사례 실행에는 원 기록 문제의 관련 원문만 제공해야 한다")


def ensure_active(state: "SupervisorState") -> None:
    """취소·기한·전체 누적 한도를 검사하며 사용량을 초기화하거나 증가시키지 않는다."""
    if state["cancellation"]["cancelled"]:
        raise RunCancelled
    if datetime.fromisoformat(state["input"].expiresAt) <= datetime.now(UTC):
        raise DeadlineExceeded
    limits = state["input"].limits
    maxima = {
        "calls": limits.maxModelCalls,
        "inputTokens": limits.maxInputTokens,
        "outputTokens": limits.maxOutputTokens,
        "toolCalls": limits.maxToolCalls,
        "retries": limits.maxRetries,
    }
    for name, maximum in maxima.items():
        used = state["usage"][name]
        if type(used) is not int or used < 0:
            raise OutputRejected("누적 사용량은 0 이상의 정수여야 한다")
        if used > maximum:
            raise BudgetExceeded("실행 전체의 누적 한도를 초과했다")


def validate_evidence_scope(request: AiRunRequest, evidence: KnowledgeEvidence) -> None:
    """전달된 접근 범위와 근거의 시스템·공개 범위가 맞는지 확인한다."""
    access = request.accessContext
    if evidence.accessScope not in access.allowedDocumentScopes:
        raise OutputRejected("허용되지 않은 자료 범위다")
    if access.purpose == "EMPLOYEE_HELP" and evidence.accessScope != "EMPLOYEE":
        raise OutputRejected("직원 상담에는 직원 공개 근거만 사용할 수 있다")
    if evidence.systemId is not None and evidence.systemId not in access.systemIds:
        raise OutputRejected("근거 시스템이 접근 범위에 없다")


def validate_statements(
    state: "SupervisorState",
    statements: list[EvidenceStatement],
    *,
    confirmed: bool,
) -> None:
    """확인 사실에는 실제 출처를 요구하고, 모든 메시지·검색 근거 참조를 대조한다."""
    message_ids = {message.id for message in state["input"].messages}
    # 서버가 보존한 표준 요청의 출처 ID도 허용한다. 생략된 과거 원문을 임의로 재조회하지 않는다.
    for problem in state["input"].problems:
        if problem.standardRequest:
            for item in (
                *problem.standardRequest.confirmedFacts,
                *problem.standardRequest.assumptions,
            ):
                message_ids.update(item.sourceMessageIds)
    for item in statements:
        if len(item.sourceMessageIds) != len(set(item.sourceMessageIds)) or len(
            item.knowledgeEvidenceIds
        ) != len(set(item.knowledgeEvidenceIds)):
            raise OutputRejected("문장 출처 ID가 중복됐다")
        if confirmed and not (item.sourceMessageIds or item.knowledgeEvidenceIds):
            raise OutputRejected("확인 사실에는 실제 출처가 필요하다")
        if (
            not set(item.sourceMessageIds) <= message_ids
            or not set(item.knowledgeEvidenceIds) <= state["evidence"].keys()
        ):
            raise OutputRejected("입력이나 검색에 없는 출처를 참조했다")


def validate_details(state: "SupervisorState", problem_ref: str, details: RequestDetails) -> None:
    """모델이 새 수용 조건을 사람 확인 완료로 임의 승격하지 못하게 한다."""
    existing = next((item for item in state["input"].problems if item.id == problem_ref), None)
    confirmed = (
        existing.standardRequest.details.acceptanceCriteria
        if existing and existing.standardRequest
        else []
    )
    for criterion in details.acceptanceCriteria:
        if criterion.confirmation == "CONFIRMED" and criterion not in confirmed:
            raise OutputRejected("서버의 기존 확인 기록에 없는 수용 조건이다")


def validate_problem_ref(state: "SupervisorState", problem_ref: str) -> None:
    """현재 실행에 존재하고 수정 대상으로 허용된 문제인지 확인한다."""
    if problem_ref not in state["problems"]:
        raise OutputRejected("존재하지 않는 문제를 참조했다")
    targets = state["input"].problemIds
    if not problem_ref.startswith("new:") and targets and problem_ref not in targets:
        raise OutputRejected("이번 실행의 대상 문제가 아니다")


def validate_questions(state: "SupervisorState") -> None:
    """문제별 질문 하나·서버의 남은 질문 수·다른 제안과의 충돌을 검사한다."""
    seen: set[str] = set()
    counts = {problem.id: problem.questionCount for problem in state["input"].problems}
    for key, question in state["questions"].items():
        validate_problem_ref(state, question.problemRef)
        if key != (question.problemRef, question.bundleRef) or question.problemRef in seen:
            raise OutputRejected("문제당 하나의 질문 묶음만 허용한다")
        if counts.get(question.problemRef, 0) >= 5:
            raise OutputRejected("문제의 질문 한도를 초과했다")
        if question.problemRef in state["ticketProposals"] or (
            state["guide"] is not None and state["guide"].problemRef == question.problemRef
        ):
            raise OutputRejected("같은 문제에 질문과 안내·티켓을 동시에 제안할 수 없다")
        seen.add(question.problemRef)
