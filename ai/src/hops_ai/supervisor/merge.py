"""역할별 결과를 검증한 뒤 새 State로 반영한다. 입력과 누적 사용량은 변경하지 않는다."""

from collections.abc import Sequence

from pydantic import ValidationError

from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.execution import RunKind
from hops_ai.contracts.roles import (
    AuthorizedKnowledgeRef,
    CuratorResult,
    InquiryResult,
    KnowledgeResult,
    RoleResult,
    TicketResult,
)
from hops_ai.contracts.wire import (
    GuideProposal,
    KnowledgeEvidence,
    ProblemProposal,
    RequestDetails,
    TicketExecution,
    TicketProposal,
)
from hops_ai.harness.errors import OutputRejected, RetrievalUnavailable
from hops_ai.supervisor.policy import validate_role_for_run
from hops_ai.supervisor.state import SupervisorState
from hops_ai.supervisor.validation import (
    ensure_active,
    get_problem_message_ids,
    problems_without_guide,
    validate_details,
    validate_evidence_scope,
    validate_problem_ref,
    validate_questions,
    validate_statements,
)


def _prepare_merge(state: SupervisorState, role: AgentRole, result: RoleResult) -> bool:
    """예약한 역할 호출인지 확인한다. 같은 호출의 동일 결과 재전달은 False를 반환한다."""
    if state["phase"] != "RUNNING" or state["finalResult"] is not None:
        raise OutputRejected("실행 중인 상태에서만 역할 결과를 반영할 수 있다")
    ensure_active(state)
    validate_role_for_run(RunKind(state["input"].kind), role)
    previous = state["roleResults"].get(result.invocationId)
    if previous is not None:
        if previous != result:
            raise OutputRejected("같은 호출 ID의 결과가 달라졌다")
        return False
    if result.invocationId not in state["pendingCalls"] or state["activeRole"] != role:
        raise OutputRejected("실행기가 예약하지 않은 역할 호출 결과다")
    return True


def _complete_merge(state: SupervisorState, result: RoleResult) -> SupervisorState:
    """검증 완료 결과를 보존하고 해당 호출의 예약을 해제한다."""
    return {
        **state,
        "activeRole": None,
        "pendingCalls": state["pendingCalls"] - {result.invocationId},
        "roleResults": {**state["roleResults"], result.invocationId: result.model_copy(deep=True)},
    }


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
    if snapshot.responsibleSystemCandidateId != proposal.responsibleSystemCandidateId:
        raise OutputRejected("티켓과 표준 요청의 책임 시스템이 다르다")
    known_ids = set(state["evidence"]) | get_problem_message_ids(state, proposal.problemRef)
    if not set(proposal.evidenceIds + snapshot.responsibilityEvidenceIds) <= known_ids:
        raise OutputRejected("티켓 책임 판단에 대상 문제에서 허용되지 않은 근거를 사용했다")
    if proposal.responsibleSystemCandidateId != problem.reportedSystemCandidateId and not (
        proposal.evidenceIds or snapshot.responsibilityEvidenceIds
    ):
        raise OutputRejected("다른 책임 시스템을 제안하려면 근거가 필요하다")
    for item in snapshot.knowledge:
        if state["evidence"].get(item.id) != item:
            raise OutputRejected("표준 요청에 허용되지 않은 지식이 포함됐다")
    validate_statements(
        state, snapshot.confirmedFacts, problem_ref=proposal.problemRef, confirmed=True
    )
    validate_statements(
        state, snapshot.assumptions, problem_ref=proposal.problemRef, confirmed=False
    )
    validate_details(state, proposal.problemRef, snapshot.details)
    if state["guide"] is not None and state["guide"].problemRef == proposal.problemRef:
        raise OutputRejected("같은 문제에 안내 확인과 티켓을 동시에 제안할 수 없다")


def _preserve_inquiry_problem(
    previous: ProblemProposal, proposal: ProblemProposal
) -> ProblemProposal:
    """Inquiry의 단순 누락을 보완하고 모호한 확인 사실 교체는 거부한다.

    확인 사실을 줄이기만 한 결과는 기존 목록을 유지한다. 새 사실을 추가하려면
    기존 사실도 함께 전달해야 한다. 기존 일부를 빼면서 다른 사실을 넣으면 단순
    누락인지 정정인지 구분할 계약이 없으므로 임의로 합치거나 삭제하지 않고 거부한다.
    사실 철회는 별도 정정 계약 또는 서버가 정정한 다음 입력 스냅샷이 필요하다.

    상세 필드의 None은 삭제 명령이 아니라 누락으로 취급한다. 명시한 non-null 값은
    갱신할 수 있지만 서버가 확인한 수용 조건은 누락해도 보존하고 임의 강등은 막는다.
    추정·미확인 목록은 Inquiry가 현재 분석으로 갱신하며 이 함수가 과거 값을 덧붙이지 않는다.

    Returns:
        기존 State·입력과 가변 목록을 공유하지 않는 보완된 문제 제안.
    """
    missing_facts = [fact for fact in previous.facts if fact not in proposal.facts]
    new_facts = [fact for fact in proposal.facts if fact not in previous.facts]
    if missing_facts and new_facts:
        raise OutputRejected("확인 사실을 누락하면서 새 사실로 교체할 수 없다")
    facts = proposal.facts if new_facts else previous.facts

    fields = {}
    for name in RequestDetails.model_fields:
        if name == "acceptanceCriteria":
            continue
        value = getattr(proposal.details, name)
        fields[name] = value if value is not None else getattr(previous.details, name)

    criteria = list(proposal.details.acceptanceCriteria)
    for criterion in previous.details.acceptanceCriteria:
        if criterion.confirmation != "CONFIRMED":
            continue
        if any(item.text == criterion.text and item != criterion for item in criteria):
            raise OutputRejected("서버가 확인한 수용 조건을 임의로 변경할 수 없다")
        if criterion not in criteria:
            criteria.append(criterion)
    details = RequestDetails(**fields, acceptanceCriteria=criteria)
    candidate = proposal.model_copy(update={"facts": facts, "details": details})
    return candidate.model_copy(deep=True)


def merge_inquiry_result(state: SupervisorState, result: InquiryResult) -> SupervisorState:
    """문의 분석 결과의 문제·질문을 검증하고 새 State로 반환한다.

    기존 UUID는 입력의 문제와 대조하고 신규 문제는 new: 참조만 허용한다.
    질문은 문제당 하나만 허용하며 동일 bundleRef·동일 내용의 재전달은 추가하지 않는다.
    기존 확인 사실·상세 값의 단순 누락은 보존하며 모호한 사실 교체는 거부한다.
    서버가 확인한 보고 시스템은 모델이 바꾸지 못한다. null 제안은 누락으로 보고
    서버 값을 유지하며, 서버 입력부터 미확인인 경우에는 최초 식별을 허용한다.
    검증 실패 시 전달받은 State와 Spring의 questionCount는 그대로 유지한다.

    Args:
        state: Inquiry 호출이 예약된 RUNNING State.
        result: 해당 invocationId의 문제·질문 제안. 입력·권한·사용량 필드는 받지 않는다.

    Returns:
        문제와 질문을 반영하고 해당 호출 예약을 해제한 새 State.

    Raises:
        OutputRejected: 잘못된 참조·출처·질문 한도·중복 결과 또는 역할 경계 위반.
    """
    try:
        result = InquiryResult.model_validate(result.model_dump(mode="json"))
    except ValidationError:
        raise OutputRejected("역할 결과의 형식이 계약과 다르다") from None
    if not _prepare_merge(state, AgentRole.INQUIRY, result):
        return state
    problems = dict(state["problems"])
    seen: set[str] = set()
    systems = {system.id for system in state["input"].systems}
    for proposal in result.problems:
        if proposal.problemRef in seen:
            raise OutputRejected("한 역할 결과에 같은 문제 참조가 중복됐다")
        seen.add(proposal.problemRef)
        if not proposal.problemRef.startswith("new:"):
            validate_problem_ref(state, proposal.problemRef)
        if (
            proposal.reportedSystemCandidateId is not None
            and proposal.reportedSystemCandidateId not in systems
        ):
            raise OutputRejected("제안한 보고 시스템이 지원 목록에 없다")
        original = next(
            (item for item in state["input"].problems if item.id == proposal.problemRef), None
        )
        if original is not None and original.reportedSystemId is not None:
            if proposal.reportedSystemCandidateId not in (None, original.reportedSystemId):
                raise OutputRejected("서버가 확인한 보고 시스템을 모델이 변경할 수 없다")
            proposal = proposal.model_copy(
                update={"reportedSystemCandidateId": original.reportedSystemId}
            )
        if proposal.processingType == "INCIDENT" and (
            original is None or original.processingType != "INCIDENT"
        ):
            raise OutputRejected("모델이 새 장애를 확정할 수 없다")
        if proposal.problemRef in problems:
            proposal = _preserve_inquiry_problem(problems[proposal.problemRef], proposal)
        if (
            proposal.problemRef in state["ticketProposals"]
            and problems[proposal.problemRef] != proposal
        ):
            raise OutputRejected("티켓 제안 후 원 문제를 변경할 수 없다")
        validate_statements(state, proposal.facts, problem_ref=proposal.problemRef, confirmed=True)
        validate_statements(
            state, proposal.assumptions, problem_ref=proposal.problemRef, confirmed=False
        )
        validate_details(state, proposal.problemRef, proposal.details)
        problems[proposal.problemRef] = proposal
    questions = dict(state["questions"])
    for question in result.questions:
        key = (question.problemRef, question.bundleRef)
        if key in questions and questions[key] != question:
            raise OutputRejected("같은 질문 묶음의 내용이 달라졌다")
        questions[key] = question
    candidate = {**state, "problems": problems, "questions": questions}
    validate_questions(candidate)
    return _complete_merge(candidate, result)


def merge_knowledge_result(
    state: SupervisorState,
    result: KnowledgeResult,
    *,
    authorized_refs: list[AuthorizedKnowledgeRef],
    verified_evidence: Sequence[KnowledgeEvidence] = (),
) -> SupervisorState:
    """권한 확인된 검색 근거와 안내를 병합한 새 State를 반환한다.

    authorized_refs와 verified_evidence는 신뢰된 검색 어댑터가 Spring의 현재 권한과
    원문·청크 digest를 검증한 뒤 전달한다. 모델 출력이나 검색 점수로 만들지 않는다.
    문서 전체 digest를 발췌문 해시와 직접 비교하지 않고, 역할이 돌려준 각 근거가
    검증된 근거 객체와 모든 필드에서 일치하는지 확인한다. 문서 참조만으로는 부족하다.
    같은 근거 ID의 다른 내용은 거부하고 검색 오류를 EMPTY로 바꾸지 않는다.

    Args:
        state: Knowledge 호출이 예약된 RUNNING State.
        result: 검색 상태·근거와 선택적인 안내 제안.
        authorized_refs: 어댑터가 현재 권한과 본문 digest를 확인한 문서 버전 목록.
        verified_evidence: 같은 어댑터가 검증한 원본 근거 객체. 결과와 따로 전달하며
            FOUND에는 해당 근거가 반드시 있어야 한다. EMPTY에는 빈 목록을 허용한다.

    Returns:
        기존 근거를 보존하고 검증된 새 근거·안내를 반영한 State.

    Raises:
        OutputRejected: 문서 버전·접근 범위·근거 ID 충돌·안내 구성 오류.
        RetrievalUnavailable: 검색 시스템 오류. 기존 근거와 State는 보존한다.
    """
    try:
        result = KnowledgeResult.model_validate(result.model_dump(mode="json"))
    except ValidationError:
        raise OutputRejected("역할 결과의 형식이 계약과 다르다") from None
    if not _prepare_merge(state, AgentRole.KNOWLEDGE, result):
        return state
    if result.search.state == "ERROR":
        raise RetrievalUnavailable
    if result.problemRef is not None:
        validate_problem_ref(state, result.problemRef)
        if result.guide is not None and result.guide.problemRef != result.problemRef:
            raise OutputRejected("안내가 검색한 문제와 다른 문제를 가리킨다")
    if result.search.state == "EMPTY" and result.guide is not None:
        raise OutputRejected("검색 0건을 근거 있는 안내로 바꿀 수 없다")
    if len(result.search.evidence) > 5:
        raise OutputRejected("검색 근거는 채택된 상위 5건 한도 이내여야 한다")
    allowed = {}
    for reference in authorized_refs:
        reference = AuthorizedKnowledgeRef.model_validate(reference.model_dump(mode="json"))
        key = (
            reference.documentId,
            reference.documentVersion,
            reference.kind,
            reference.accessScope,
        )
        if key in allowed and allowed[key] != reference:
            raise OutputRejected("같은 허용 문서 버전의 digest가 다르다")
        allowed[key] = reference
    verified_by_id: dict[str, KnowledgeEvidence] = {}
    for item in verified_evidence:
        if not isinstance(item, KnowledgeEvidence):
            raise OutputRejected("어댑터가 검증한 근거의 형식이 잘못됐다")
        try:
            checked = KnowledgeEvidence.model_validate(item.model_dump(mode="json"))
        except ValidationError:
            raise OutputRejected("어댑터가 검증한 근거의 형식이 잘못됐다") from None
        if checked.id in verified_by_id and verified_by_id[checked.id] != checked:
            raise OutputRejected("같은 검증 근거 ID의 내용이 다르다")
        verified_by_id[checked.id] = checked
    evidence = dict(state["evidence"])
    for item in result.search.evidence:
        key = (item.documentId, item.documentVersion, item.kind, item.accessScope)
        if key not in allowed:
            raise OutputRejected("현재 권한 확인에서 허용되지 않은 문서 버전이다")
        validate_evidence_scope(state["input"], item)
        if verified_by_id.get(item.id) != item:
            raise OutputRejected("검색 결과가 어댑터에서 검증한 원본 근거와 다르다")
        if item.id in evidence and evidence[item.id] != item:
            raise OutputRejected("같은 근거 ID의 내용 또는 버전이 달라졌다")
        # 모델이 재작성한 객체 대신 별도로 전달받은 검증 객체의 독립 복사본을 보존한다.
        evidence[item.id] = verified_by_id[item.id].model_copy(deep=True)
    guide = result.guide if result.guide is not None else state["guide"]
    candidate = {**state, "evidence": evidence, "guide": guide}
    if guide is not None:
        if state["input"].kind == RunKind.CASE_DRAFT:
            raise OutputRejected("사례 초안 실행은 상담 안내를 반환하지 않는다")
        validate_guide(candidate, guide)
    validate_questions(candidate)
    return _complete_merge(candidate, result)


def merge_ticket_result(
    state: SupervisorState,
    result: TicketResult,
    *,
    verified_results: tuple[TicketExecution, ...] = (),
) -> SupervisorState:
    """문제별 티켓 제안을 검증하고 같은 문제의 최종 유효 제안을 보존한다.

    실제 저장·배정은 하지 않는다. STORED·PENDING·UNKNOWN 작업에는 새 제안을 금지하고
    서버가 NOT_CREATED로 확인한 작업만 다시 제안할 수 있다.
    어느 한 제안이 잘못돼도 이 함수가 받은 State의 기존 제안은 바뀌지 않는다.

    Args:
        state: Ticket 호출이 예약된 RUNNING State.
        result: 같은 실행에서 검증할 문제별 티켓 제안 목록.

    Returns:
        기존 티켓 제안에 새 유효 제안을 반영한 State. 실제 업무 저장은 수행하지 않는다.

    Raises:
        OutputRejected: 문제·책임 시스템·출처·기존 작업 또는 다른 제안과 충돌하는 경우.
    """
    try:
        result = TicketResult.model_validate(result.model_dump(mode="json"))
    except ValidationError:
        raise OutputRejected("역할 결과의 형식이 계약과 다르다") from None
    if not _prepare_merge(state, AgentRole.TICKET, result):
        return state
    proposals = dict(state["ticketProposals"])
    if (
        not isinstance(verified_results, tuple)
        or any(not isinstance(item, TicketExecution) for item in verified_results)
        or list(verified_results) != result.creationResults
    ):
        raise OutputRejected("Spring 도구에서 검증하지 않은 티켓 생성 결과다")
    creations = dict(state["ticketCreationResults"])
    problems = dict(state["problems"])
    seen: set[str] = set()
    for proposal in result.proposals:
        if proposal.problemRef in seen:
            raise OutputRejected("한 결과에 같은 문제의 티켓이 중복됐다")
        seen.add(proposal.problemRef)
        validate_ticket(state, proposal)
        proposals[proposal.problemRef] = proposal
        problem = state["problems"][proposal.problemRef]
        if proposal.processingType != problem.processingType:
            # 담당자 검토로 넘기기로 한 문제는 최종 결과에서도 같은 처리 유형으로 보인다.
            problems[proposal.problemRef] = problem.model_copy(
                update={"processingType": proposal.processingType}
            )
    if len({item.problemRef for item in verified_results}) != len(verified_results):
        raise OutputRejected("티켓 생성 결과의 문제 참조가 중복됐다")
    for outcome in verified_results:
        if outcome.problemRef not in seen:
            raise OutputRejected("제안하지 않은 문제의 티켓 생성 결과다")
        remote = outcome.result
        if remote is not None and (
            remote.runId != state["input"].runId
            or remote.inquiryVersion != state["input"].inquiryVersion
            or remote.problemRef != outcome.problemRef
        ):
            raise OutputRejected("티켓 생성 결과가 실행과 다르다")
        if outcome.problemRef in creations and creations[outcome.problemRef] != outcome:
            raise OutputRejected("이미 확인한 티켓 생성 결과를 바꿀 수 없다")
        creations[outcome.problemRef] = outcome.model_copy(deep=True)
    candidate = {
        **state,
        "problems": problems,
        "ticketProposals": proposals,
        "ticketCreationResults": creations,
    }
    validate_questions(candidate)
    return _complete_merge(candidate, result)


def merge_curator_result(
    state: SupervisorState,
    result: CuratorResult,
    *,
    allowed_entity_ids: dict[str, set[str]],
) -> SupervisorState:
    """원 처리 기록 하나에 대한 사례 초안을 검증하고 새 State에 보존한다.

    allowed_entity_ids는 실행기가 제공하는 관계 종류별 기존 엔티티 ID 목록이다.
    새 엔티티나 관계를 생성하지 않으며, 적용 시스템 버전도 원 처리 기록과 일치해야 한다.
    reusable=false는 오류가 아닌 정상 결과다. 초안의 승인·검색 공개는 Spring이 결정한다.

    Args:
        state: Curator 호출이 예약된 CASE_DRAFT의 RUNNING State.
        result: 원 처리 기록에 대응하는 사례 초안 또는 재사용 없음 결과.
        allowed_entity_ids: 실행기가 검증한 관계 종류별 기존 엔티티 ID 집합.

    Returns:
        caseProposal을 보존하고 해당 호출 예약을 해제한 새 State.

    Raises:
        OutputRejected: 원 기록·버전·출처·엔티티·역할 범위를 벗어나는 경우.
    """
    try:
        result = CuratorResult.model_validate(result.model_dump(mode="json"))
    except ValidationError:
        raise OutputRejected("역할 결과의 형식이 계약과 다르다") from None
    if not _prepare_merge(state, AgentRole.CURATOR, result):
        return state
    proposal = result.proposal
    record = state["input"].workRecord
    if record is None:
        raise OutputRejected("사례 원 처리 기록이 없다")
    if proposal.reusable:
        if proposal.appliesToSystemVersion != record.appliesToSystemVersion:
            raise OutputRejected("적용 시스템 버전을 원 기록과 다르게 추정할 수 없다")
        if proposal.accessScope not in state["input"].accessContext.allowedDocumentScopes:
            raise OutputRejected("허용되지 않은 사례 공개 범위다")
        message_ids = {message.id for message in state["input"].messages}
        if len(proposal.sourceMessageIds) != len(set(proposal.sourceMessageIds)):
            raise OutputRejected("사례 원문 출처 ID가 중복됐다")
        if not set(proposal.sourceMessageIds) <= message_ids:
            raise OutputRejected("원 처리 기록과 관련 없는 원문 출처다")
        for link in proposal.entityLinks:
            if link.targetId not in allowed_entity_ids.get(link.relation, set()):
                raise OutputRejected("허용된 기존 엔티티가 아니다")
            if link.relation == "APPLIES_TO" and link.targetId != record.systemId:
                raise OutputRejected("사례의 적용 시스템이 원 처리 기록과 다르다")
            if not set(link.evidenceIds) <= (set(state["evidence"]) | message_ids):
                raise OutputRejected("사례 관계에 실제로 없는 근거를 사용했다")
    return _complete_merge({**state, "caseProposal": proposal}, result)
