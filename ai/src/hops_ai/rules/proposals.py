"""문제·사실·안내·티켓 제안을 입력 스냅샷과 대조하는 검증. State를 수정하지 않는다.

에이전트가 모델 출력을 반환하기 전에 쓰고 Supervisor의 병합 단계도 같은 규칙을 쓴다.
어느 쪽 패키지도 불러오지 않는다.
"""

from hops_ai.contracts.state import SupervisorState
from hops_ai.contracts.wire import (
    EvidenceStatement,
    GuideProposal,
    ProblemProposal,
    RequestDetails,
    TicketProposal,
)
from hops_ai.harness.errors import OutputRejected
from hops_ai.rules.progress import (
    get_problem_knowledge,
    get_problem_message_ids,
    problems_without_guide,
    validate_evidence_scope,
)


def validate_statements(
    state: "SupervisorState",
    statements: list[EvidenceStatement],
    *,
    confirmed: bool,
    problem_ref: str,
) -> None:
    """문장 출처의 존재와 대상 문제의 메시지 연결을 함께 확인한다.

    Args:
        state: 서버가 확정한 입력과 이미 검증된 검색 근거를 가진 State.
        statements: 문제의 확인 사실 또는 추정. 추정도 출처를 지정했다면 같은
            연결 검사를 적용하지만 출처 자체가 없는 추정은 허용한다.
        confirmed: True이면 실제 메시지 또는 지식 근거가 하나 이상 필요하다.
        problem_ref: 이 문장을 적용할 기존 문제 UUID 또는 신규 new: 참조.

    메시지 연결은 get_problem_message_ids로 검사하고 지식 근거는 이미 검증된
    evidence map과 대조한다. 두 출처 종류를 임의로 다른 문제의 원문과 섞지 않는다.

    Raises:
        OutputRejected: 출처가 없거나 중복됐거나, 대상 문제에 사용할 수 없는 출처인 경우.
    """
    message_ids = get_problem_message_ids(state, problem_ref)
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
            raise OutputRejected("대상 문제에 허용되지 않은 메시지 또는 검색 출처를 참조했다")


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


def validate_guide(state: SupervisorState, guide: GuideProposal) -> None:
    """안내 대상과 실제 직원 공개 가이드 근거를 확인한다."""
    validate_problem_ref(state, guide.problemRef)
    if not guide.evidenceIds or len(guide.evidenceIds) != len(set(guide.evidenceIds)):
        raise OutputRejected("안내에는 중복 없는 근거가 필요하다")
    items = []
    for evidence_id in guide.evidenceIds:
        item = state["evidence"].get(evidence_id)
        if item is None or item.accessScope != "EMPLOYEE":
            raise OutputRejected("안내에 허용된 직원 공개 근거가 없다")
        validate_evidence_scope(state["input"], item)
        items.append(item)
    if not any(item.kind == "GUIDE" for item in items):
        raise OutputRejected("직원 안내에는 가이드 근거가 필요하다")
    if guide.problemRef in state["ticketProposals"]:
        raise OutputRejected("같은 문제에 안내 확인과 티켓을 동시에 제안할 수 없다")


def _is_review_handoff(
    state: SupervisorState, problem: ProblemProposal, proposal: TicketProposal
) -> bool:
    """안내할 가이드를 찾지 못한 사용법 문제를 담당자 검토 티켓으로 넘기는 경우인지.

    사용법(KNOWLEDGE_QA)은 안내로 끝나는 처리 유형이라 티켓 유형이 없다. Knowledge가 그 문제를
    오류 없이 검색했지만 안내를 만들지 못했을 때에 한해 REVIEW_REQUIRED 티켓을 허용한다.
    검색하지 않은 문제나 다른 문제의 결과로는 허용하지 않으며 다른 유형 불일치는 계속 거부한다.
    """
    return (
        problem.processingType == "KNOWLEDGE_QA"
        and proposal.processingType == "REVIEW_REQUIRED"
        and problem.problemRef in problems_without_guide(state)
    )


def validate_ticket(state: SupervisorState, proposal: TicketProposal) -> None:
    """티켓 대상·보고/책임 시스템·기존 저장 결과·표준 요청의 출처를 대조한다.

    서버가 확인한 보고 시스템은 고정한다. 내부 상태가 달라졌어도 그 값을
    책임 시스템의 무근거 변경에 이용할 수 없으며, 정정은 다음 서버 입력으로 받는다.
    """
    validate_problem_ref(state, proposal.problemRef)
    problem = state["problems"][proposal.problemRef]
    if proposal.processingType != problem.processingType and not _is_review_handoff(
        state, problem, proposal
    ):
        raise OutputRejected("티켓과 문제의 처리 유형이 다르다")
    original = next(
        (item for item in state["input"].problems if item.id == proposal.problemRef), None
    )
    if (
        original is not None
        and original.reportedSystemId is not None
        and problem.reportedSystemCandidateId != original.reportedSystemId
    ):
        raise OutputRejected("서버가 확인한 보고 시스템이 변경됐다")
    if original is not None and original.state in ("RESOLVED", "OUT_OF_SCOPE"):
        raise OutputRejected("종료된 문제에 새 티켓을 제안할 수 없다")
    for ids in (proposal.evidenceIds, proposal.standardRequestSnapshot.responsibilityEvidenceIds):
        if len(ids) != len(set(ids)):
            raise OutputRejected("티켓 근거 ID가 중복됐다")
    if problem.reportedSystemCandidateId is None or problem.intent == "OUT_OF_SCOPE":
        raise OutputRejected("보고 시스템 미확인 또는 범위 밖 문제에는 티켓을 제안할 수 없다")
    for work in state["input"].existingWorkItems:
        if work.problemId == proposal.problemRef and work.outcome != "NOT_CREATED":
            raise OutputRejected("이미 저장됐거나 저장 확인 중인 문제에 새 티켓을 제안할 수 없다")
    systems = {system.id for system in state["input"].systems}
    if proposal.responsibleSystemCandidateId not in systems:
        raise OutputRejected("책임 시스템이 지원 시스템 목록에 없다")
    snapshot = proposal.standardRequestSnapshot
    if len(snapshot.knowledge) > 5:
        raise OutputRejected("표준 요청의 지식 근거는 최대 5건이다")
    if snapshot.responsibleSystemCandidateId != proposal.responsibleSystemCandidateId:
        raise OutputRejected("티켓과 표준 요청의 책임 시스템이 다르다")
    scoped_knowledge = get_problem_knowledge(state, proposal.problemRef)
    known_ids = set(scoped_knowledge) | get_problem_message_ids(state, proposal.problemRef)
    if not set(proposal.evidenceIds + snapshot.responsibilityEvidenceIds) <= known_ids:
        raise OutputRejected("티켓 책임 판단에 대상 문제에서 허용되지 않은 근거를 사용했다")
    if proposal.responsibleSystemCandidateId != problem.reportedSystemCandidateId and not (
        proposal.evidenceIds or snapshot.responsibilityEvidenceIds
    ):
        raise OutputRejected("다른 책임 시스템을 제안하려면 근거가 필요하다")
    for item in snapshot.knowledge:
        if scoped_knowledge.get(item.id) != item:
            raise OutputRejected("표준 요청에 대상 문제의 지식이 아닌 근거가 포함됐다")
    for fact in [*snapshot.confirmedFacts, *snapshot.assumptions]:
        if not set(fact.knowledgeEvidenceIds) <= scoped_knowledge.keys():
            raise OutputRejected("표준 요청의 사실에 다른 문제의 지식 근거가 포함됐다")
    validate_statements(
        state, snapshot.confirmedFacts, problem_ref=proposal.problemRef, confirmed=True
    )
    validate_statements(
        state, snapshot.assumptions, problem_ref=proposal.problemRef, confirmed=False
    )
    validate_details(state, proposal.problemRef, snapshot.details)
    if state["guide"] is not None and state["guide"].problemRef == proposal.problemRef:
        raise OutputRejected("같은 문제에 안내 확인과 티켓을 동시에 제안할 수 없다")
