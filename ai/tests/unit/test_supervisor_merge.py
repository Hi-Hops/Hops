"""전문 역할별 병합의 정상·중복·거부·오류 경계를 검증한다."""

from copy import deepcopy

import pytest
from pydantic import ValidationError

from hops_ai.contracts.domain import AgentRole, ProcessingType
from hops_ai.contracts.execution import RunKind
from hops_ai.contracts.roles import (
    CuratorResult,
    InquiryResult,
    KnowledgeResult,
    SearchResult,
    TicketResult,
)
from hops_ai.contracts.wire import (
    AcceptanceCriterion,
    EvidenceStatement,
    GuideProposal,
    ProblemProposal,
    QuestionProposal,
)
from hops_ai.harness.errors import OutputRejected, RetrievalUnavailable
from hops_ai.supervisor.merge import (
    merge_curator_result,
    merge_inquiry_result,
    merge_knowledge_result,
    merge_ticket_result,
)
from hops_ai.supervisor.state import transition_phase
from tests.supervisor_fixtures import (
    EVIDENCE,
    OTHER_SYSTEM,
    PROBLEM,
    SYSTEM,
    authorize,
    make_case,
    make_evidence,
    make_problem,
    make_request,
    make_ticket,
    reserve,
    running_state,
    uid,
)


def question(
    *, bundle="q1", text="어떤 오류 문구가 보이나요?", problem=PROBLEM
) -> QuestionProposal:
    """지정한 문제에 보낼 테스트용 질문 묶음을 만든다."""
    return QuestionProposal(
        problemRef=problem, bundleRef=bundle, text=text, requestedFields=["actualBehavior"]
    )


def test_inquiry_merge_preserves_question_count_and_handles_replay() -> None:
    """같은 호출과 같은 질문 묶음의 재전달로 질문을 추가하거나 횟수를 올리지 않는다."""
    state = reserve(running_state(make_request(question_count=4)), AgentRole.INQUIRY, "i1")
    result = InquiryResult(invocationId="i1", problems=[make_problem()], questions=[question()])
    merged = merge_inquiry_result(state, result)
    assert not state["questions"]
    assert merged["input"].problems[0].questionCount == 4
    assert len(merged["questions"]) == 1
    assert not merged["pendingCalls"] and merged["activeRole"] is None
    assert merge_inquiry_result(merged, result) is merged
    second = result.model_copy(update={"invocationId": "i2"})
    again = merge_inquiry_result(reserve(merged, AgentRole.INQUIRY, "i2"), second)
    assert len(again["questions"]) == 1
    result.problems.clear()
    assert merged["problems"][PROBLEM].summary == "첨부파일 열람 실패"


def test_changed_result_for_same_invocation_is_rejected() -> None:
    """같은 호출 ID에 다른 결과가 도착하면 기존 결과를 교체하지 않는다."""
    state = reserve(running_state(), AgentRole.INQUIRY, "i1")
    merged = merge_inquiry_result(
        state, InquiryResult(invocationId="i1", problems=[], questions=[])
    )
    with pytest.raises(OutputRejected):
        merge_inquiry_result(
            merged, InquiryResult(invocationId="i1", problems=[], questions=[question()])
        )


@pytest.mark.parametrize("reason", ["limit", "new_bundle", "changed_bundle", "unknown_problem"])
def test_invalid_question_is_rejected_atomically(reason) -> None:
    """질문 한도·묶음 충돌·없는 문제 참조의 실패가 기존 State를 바꾸지 않는다."""
    state = running_state(make_request(question_count=5 if reason == "limit" else 0))
    if reason in ("new_bundle", "changed_bundle"):
        state = merge_inquiry_result(
            reserve(state, AgentRole.INQUIRY, "i1"),
            InquiryResult(invocationId="i1", problems=[], questions=[question()]),
        )
    new_question = question()
    if reason == "new_bundle":
        new_question = question(bundle="q2")
    elif reason == "changed_bundle":
        new_question = question(text="다른 질문")
    elif reason == "unknown_problem":
        new_question = question(problem=uid(999))
    state = reserve(state, AgentRole.INQUIRY, "i2")
    before = deepcopy(state)
    with pytest.raises(OutputRejected):
        merge_inquiry_result(
            state, InquiryResult(invocationId="i2", problems=[], questions=[new_question])
        )
    assert state == before


def test_new_problem_uses_temporary_ref() -> None:
    """신규 문제는 DB UUID를 만들어내는 대신 new: 참조로 추가한다."""
    state = reserve(running_state(), AgentRole.INQUIRY, "i1")
    result = InquiryResult(invocationId="i1", problems=[make_problem("new:download")], questions=[])
    assert "new:download" in merge_inquiry_result(state, result)["problems"]


@pytest.mark.parametrize("reason", ["unknown_source", "incident", "confirmed_criterion"])
def test_inquiry_rejects_unsupported_facts_and_confirmations(reason) -> None:
    """없는 출처·근거 없는 사실·모델의 장애 확정과 사람 확인 승격을 거부한다."""
    proposal = make_problem()
    if reason == "unknown_source":
        proposal = proposal.model_copy(
            update={
                "facts": [
                    EvidenceStatement(
                        text="현상이 확인됨", sourceMessageIds=[uid(999)], knowledgeEvidenceIds=[]
                    )
                ]
            }
        )
    elif reason == "incident":
        proposal = proposal.model_copy(update={"processingType": ProcessingType.INCIDENT})
    else:
        details = proposal.details.model_copy(
            update={
                "acceptanceCriteria": [
                    AcceptanceCriterion(
                        text="신규 기능", confirmation="CONFIRMED", confirmedBy=uid(200)
                    )
                ]
            }
        )
        proposal = proposal.model_copy(update={"details": details})
    with pytest.raises(OutputRejected):
        merge_inquiry_result(
            reserve(running_state(), AgentRole.INQUIRY, "i1"),
            InquiryResult(invocationId="i1", problems=[proposal], questions=[]),
        )


def test_knowledge_merges_only_authorized_version_and_public_guide() -> None:
    """현재 허용 문서 버전만 근거로 반영하고 직원용 가이드를 연결한다."""
    item = make_evidence()
    guide = GuideProposal(
        problemRef=PROBLEM, messageText="파일 열람 권한을 확인해 주세요", evidenceIds=[EVIDENCE]
    )
    result = KnowledgeResult(
        invocationId="k1",
        search=SearchResult(state="FOUND", evidence=[item], error=None),
        guide=guide,
    )
    state = reserve(running_state(), AgentRole.KNOWLEDGE, "k1")
    merged = merge_knowledge_result(state, result, authorized_refs=[authorize(item)])
    assert merged["guide"] == guide and merged["evidence"][EVIDENCE] == item
    assert state["evidence"] == {}


@pytest.mark.parametrize(
    "reason", ["missing_authorization", "wrong_version", "staff_scope", "other_system"]
)
def test_knowledge_rejects_invalid_authorization(reason) -> None:
    """미허용 문서·다른 버전·직원 비공개 범위·시스템 밖의 근거를 거부한다."""
    item = make_evidence()
    refs = [authorize(item)]
    if reason == "missing_authorization":
        refs = []
    elif reason == "wrong_version":
        refs = [authorize(make_evidence(documentVersion=2))]
    elif reason == "staff_scope":
        item = make_evidence(accessScope="SYSTEM_STAFF")
        refs = [authorize(item)]
    else:
        item = make_evidence(systemId=uid(999))
        refs = [authorize(item)]
    state = reserve(running_state(), AgentRole.KNOWLEDGE, "k1")
    with pytest.raises(OutputRejected):
        merge_knowledge_result(
            state,
            KnowledgeResult(
                invocationId="k1",
                search=SearchResult(state="FOUND", evidence=[item], error=None),
                guide=None,
            ),
            authorized_refs=refs,
        )
    assert state["evidence"] == {}


def test_conflicting_evidence_does_not_replace_previous_evidence() -> None:
    """같은 근거 ID에 새 버전이나 내용이 오면 별도 ID를 요구한다."""
    item = make_evidence()
    result = KnowledgeResult(
        invocationId="k1",
        search=SearchResult(state="FOUND", evidence=[item], error=None),
        guide=None,
    )
    state = merge_knowledge_result(
        reserve(running_state(), AgentRole.KNOWLEDGE, "k1"),
        result,
        authorized_refs=[authorize(item)],
    )
    changed = make_evidence(excerpt="변경된 내용")
    with pytest.raises(OutputRejected):
        merge_knowledge_result(
            reserve(state, AgentRole.KNOWLEDGE, "k2"),
            KnowledgeResult(
                invocationId="k2",
                search=SearchResult(state="FOUND", evidence=[changed], error=None),
                guide=None,
            ),
            authorized_refs=[authorize(changed)],
        )
    assert state["evidence"][EVIDENCE].excerpt == item.excerpt


def test_empty_search_is_normal_but_error_is_not_empty() -> None:
    """검색 0건은 정상 결과로 보존하고 검색 장애는 예외로 구분한다."""
    state = merge_knowledge_result(
        reserve(running_state(), AgentRole.KNOWLEDGE, "k1"),
        KnowledgeResult(
            invocationId="k1",
            search=SearchResult(state="EMPTY", evidence=[], error=None),
            guide=None,
        ),
        authorized_refs=[],
    )
    assert "k1" in state["roleResults"]
    error = {
        "code": "SEARCH_UNAVAILABLE",
        "message": "검색 실패",
        "requestId": uid(600),
        "retryable": True,
        "fieldErrors": [],
    }
    with pytest.raises(RetrievalUnavailable):
        merge_knowledge_result(
            reserve(state, AgentRole.KNOWLEDGE, "k2"),
            KnowledgeResult(
                invocationId="k2",
                search=SearchResult(state="ERROR", evidence=[], error=error),
                guide=None,
            ),
            authorized_refs=[],
        )


@pytest.mark.parametrize("outcome", ["STORED", "PENDING", "UNKNOWN"])
def test_existing_work_blocks_new_ticket(outcome) -> None:
    """저장 성공·저장 중·미확인은 미생성과 구분해 새 티켓 제안을 막는다."""
    state = reserve(running_state(make_request(outcome=outcome)), AgentRole.TICKET, "t1")
    with pytest.raises(OutputRejected):
        merge_ticket_result(state, TicketResult(invocationId="t1", proposals=[make_ticket()]))
    assert not state["ticketProposals"]


def test_not_created_work_can_receive_a_new_proposal() -> None:
    """Spring이 미생성을 확인한 같은 작업에는 제안을 다시 만들 수 있다."""
    state = reserve(running_state(make_request(outcome="NOT_CREATED")), AgentRole.TICKET, "t1")
    merged = merge_ticket_result(state, TicketResult(invocationId="t1", proposals=[make_ticket()]))
    assert merged["ticketProposals"][PROBLEM].responsibleSystemCandidateId == SYSTEM
    assert merged["input"].existingWorkItems[0].outcome == "NOT_CREATED"


@pytest.mark.parametrize(
    "reason", ["unknown_system", "different_system_without_evidence", "unknown_evidence"]
)
def test_invalid_ticket_references_are_rejected(reason) -> None:
    """책임 시스템과 출처를 실제 입력·검색 결과에 대조한다."""
    ticket = make_ticket(system=uid(999) if reason == "unknown_system" else OTHER_SYSTEM)
    if reason == "unknown_evidence":
        ticket = ticket.model_copy(update={"evidenceIds": [uid(999)]})
    with pytest.raises(OutputRejected):
        merge_ticket_result(
            reserve(running_state(), AgentRole.TICKET, "t1"),
            TicketResult(invocationId="t1", proposals=[ticket]),
        )


def test_question_and_ticket_for_same_problem_conflict_in_both_orders() -> None:
    """병합 순서와 관계없이 같은 문제의 질문과 티켓 동시 제안을 막는다."""
    state = merge_inquiry_result(
        reserve(running_state(), AgentRole.INQUIRY, "i1"),
        InquiryResult(invocationId="i1", problems=[], questions=[question()]),
    )
    with pytest.raises(OutputRejected):
        merge_ticket_result(
            reserve(state, AgentRole.TICKET, "t1"),
            TicketResult(invocationId="t1", proposals=[make_ticket()]),
        )
    ticket_state = merge_ticket_result(
        reserve(running_state(), AgentRole.TICKET, "t1"),
        TicketResult(invocationId="t1", proposals=[make_ticket()]),
    )
    with pytest.raises(OutputRejected):
        merge_inquiry_result(
            reserve(ticket_state, AgentRole.INQUIRY, "i1"),
            InquiryResult(invocationId="i1", problems=[], questions=[question()]),
        )


@pytest.mark.parametrize("reusable", [True, False])
def test_curator_proposal_and_no_reusable_case_are_normal(reusable) -> None:
    """사례 초안과 재사용 없음 모두 정상 결과로 보존하며 업무 상태는 바꾸지 않는다."""
    state = reserve(running_state(make_request(kind=RunKind.CASE_DRAFT)), AgentRole.CURATOR, "c1")
    proposal = make_case(reusable=reusable)
    merged = merge_curator_result(
        state,
        CuratorResult(invocationId="c1", proposal=proposal),
        allowed_entity_ids={"APPLIES_TO": {SYSTEM}},
    )
    assert merged["caseProposal"] == proposal
    assert merged["input"].workRecord.confirmationState == "PENDING"


@pytest.mark.parametrize("reason", ["unknown_entity", "guessed_version", "unrelated_message"])
def test_curator_rejects_unverified_links_and_sources(reason) -> None:
    """기존 엔티티·원 처리 기록 버전·관련 원문의 경계를 유지한다."""
    proposal = make_case()
    allowed = {"APPLIES_TO": {SYSTEM}}
    if reason == "unknown_entity":
        allowed = {}
    elif reason == "guessed_version":
        proposal = proposal.model_copy(update={"appliesToSystemVersion": "2.0"})
    else:
        proposal = proposal.model_copy(update={"sourceMessageIds": [uid(999)]})
    with pytest.raises(OutputRejected):
        merge_curator_result(
            reserve(running_state(make_request(kind=RunKind.CASE_DRAFT)), AgentRole.CURATOR, "c1"),
            CuratorResult(invocationId="c1", proposal=proposal),
            allowed_entity_ids=allowed,
        )


def test_unreserved_and_terminal_results_are_rejected() -> None:
    """호출 예약이 없거나 종료된 실행에는 전문 결과를 추가하지 않는다."""
    result = InquiryResult(invocationId="i1", problems=[], questions=[])
    with pytest.raises(OutputRejected):
        merge_inquiry_result(running_state(), result)
    terminal = transition_phase(running_state(), "FAILED")
    with pytest.raises(OutputRejected):
        merge_inquiry_result(reserve(terminal, AgentRole.INQUIRY, "i1"), result)


def test_duplicate_statement_sources_are_rejected() -> None:
    """같은 출처를 반복해 사실의 근거가 여러 개인 것처럼 표현하지 못한다."""
    proposal = make_problem()
    fact = proposal.facts[0].model_copy(update={"sourceMessageIds": [uid(20), uid(20)]})
    proposal = proposal.model_copy(update={"facts": [fact]})
    with pytest.raises(OutputRejected):
        merge_inquiry_result(
            reserve(running_state(), AgentRole.INQUIRY, "i1"),
            InquiryResult(invocationId="i1", problems=[proposal], questions=[]),
        )


@pytest.mark.parametrize("problem_state", ["RESOLVED", "OUT_OF_SCOPE"])
def test_terminal_problem_cannot_get_another_ticket(problem_state) -> None:
    """기존 작업 목록이 비어 있어도 이미 종료된 문제를 새 티켓으로 재개하지 않는다."""
    from hops_ai.contracts.wire import AiRunRequest

    payload = make_request().model_dump(mode="json")
    payload["problems"][0]["state"] = problem_state
    state = reserve(running_state(AiRunRequest.model_validate(payload)), AgentRole.TICKET, "t1")
    with pytest.raises(OutputRejected):
        merge_ticket_result(state, TicketResult(invocationId="t1", proposals=[make_ticket()]))


def test_ticket_batch_failure_preserves_all_previous_proposals() -> None:
    """복수 제안 중 뒤 항목이 실패해도 앞 항목을 부분 반영하지 않는다."""
    state = reserve(running_state(), AgentRole.TICKET, "t1")
    before = deepcopy(state)
    with pytest.raises(OutputRejected):
        merge_ticket_result(
            state,
            TicketResult(invocationId="t1", proposals=[make_ticket(), make_ticket("new:missing")]),
        )
    assert state == before


def test_case_draft_knowledge_cannot_create_chat_guide() -> None:
    """사례 실행에서 검색하더라도 문의자 상담 안내를 반환하지 않는다."""
    item = make_evidence()
    guide = GuideProposal(problemRef=PROBLEM, messageText="직원용 안내", evidenceIds=[EVIDENCE])
    with pytest.raises(OutputRejected):
        merge_knowledge_result(
            reserve(
                running_state(make_request(kind=RunKind.CASE_DRAFT)), AgentRole.KNOWLEDGE, "k1"
            ),
            KnowledgeResult(
                invocationId="k1",
                search=SearchResult(state="FOUND", evidence=[item], error=None),
                guide=guide,
            ),
            authorized_refs=[authorize(item)],
        )


def test_sourceless_fact_is_rejected_by_common_wire() -> None:
    """최신 공통 DTO는 출처 없는 사실을 역할 병합 전에 거부한다."""
    payload = make_problem().model_dump(mode="json")
    payload["facts"] = [
        {
            "text": "근거 없는 사실",
            "sourceMessageIds": [],
            "knowledgeEvidenceIds": [],
        }
    ]
    with pytest.raises(ValidationError):
        ProblemProposal.model_validate(payload)


@pytest.mark.parametrize(
    "state,evidence,error",
    [
        ("FOUND", [], None),
        ("EMPTY", [make_evidence()], None),
        ("ERROR", [], None),
    ],
)
def test_search_result_does_not_hide_errors_as_empty(state, evidence, error) -> None:
    """검색 상태에 맞지 않는 근거·오류 조합을 거부한다."""
    with pytest.raises(ValidationError):
        SearchResult(state=state, evidence=evidence, error=error)
