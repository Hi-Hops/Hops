import pytest

from hops_ai.contracts.agent_1 import Evidence, IntakeProblem, Problem, TicketProposal
from hops_ai.contracts.analysis import AnalysisOutcome
from hops_ai.harness.errors import OutputRejected
from hops_ai.harness.guardrails.outcome import validate_outcome

EXISTING = "00000000-0000-4000-8000-000000000201"
EVIDENCE = Evidence(
    id="msg-601-r1",
    kind="MESSAGE",
    sourceId="00000000-0000-4000-8000-000000000601",
    sourceVersion="1",
    summary="가상 문의",
    observedAt=None,
)


def problem(ref="new:p1", action="ASK_USER", count=0, evidence=("msg-601-r1",)) -> IntakeProblem:
    return IntakeProblem(
        ref=ref,
        summary="VPN 접속 오류",
        category="NETWORK_ACCESS",
        questionCount=count,
        nextAction=action,
        question="오류 코드를 알려 주세요." if action == "ASK_USER" else None,
        evidenceIds=list(evidence),
    )


def proposal(
    problem_ref=EXISTING, department="NETWORK", ref="new:work-1", evidence=("msg-601-r1",)
):
    return TicketProposal(
        ref=ref,
        problemRef=problem_ref,
        workScope="VPN 연결 확인",
        title="VPN 접속 오류",
        description="가상 설명",
        category="NETWORK_ACCESS",
        departmentCode=department,
        purpose="INCIDENT",
        diagnosisSummary="가상 진단",
        handoffReason="가상 이관 사유",
        uncertainty=None,
        evidenceIds=list(evidence),
    )


def outcome(problems, proposals=(), evidence=(EVIDENCE,)) -> AnalysisOutcome:
    return AnalysisOutcome(
        answer="답",
        problems=list(problems),
        ticket_proposals=list(proposals),
        evidence=list(evidence),
    )


@pytest.fixture
def data(make_input):
    return make_input(
        existing_problems=[
            Problem(
                ref=EXISTING, summary="VPN 접속 오류", category="NETWORK_ACCESS", questionCount=1
            )
        ]
    )


def test_a_question_turn_for_a_new_problem_is_valid(make_input) -> None:
    validate_outcome(make_input(), outcome([problem()]))


def test_a_ticket_proposal_for_an_existing_problem_is_valid(data) -> None:
    validate_outcome(data, outcome([problem(EXISTING, "PROPOSE_TICKETS", count=1)], [proposal()]))


def test_duplicate_problem_refs_are_rejected(make_input) -> None:
    with pytest.raises(OutputRejected):
        validate_outcome(make_input(), outcome([problem(), problem()]))


def test_a_new_problem_must_start_with_zero_questions(make_input) -> None:
    with pytest.raises(OutputRejected):
        validate_outcome(make_input(), outcome([problem(count=1)]))


def test_an_invented_existing_problem_ref_is_rejected(data) -> None:
    invented = "00000000-0000-4000-8000-000000000999"

    with pytest.raises(OutputRejected):
        validate_outcome(
            data, outcome([problem(invented, "PROPOSE_TICKETS")], [proposal(invented)])
        )


def test_the_model_cannot_change_the_servers_question_count(data) -> None:
    with pytest.raises(OutputRejected):
        validate_outcome(
            data, outcome([problem(EXISTING, "PROPOSE_TICKETS", count=0)], [proposal()])
        )


def test_asking_again_after_the_question_limit_is_rejected(make_input) -> None:
    exhausted = make_input(
        existing_problems=[
            Problem(ref=EXISTING, summary="확인 불가", category="OTHER", questionCount=3)
        ]
    )

    with pytest.raises(OutputRejected):
        validate_outcome(exhausted, outcome([problem(EXISTING, "ASK_USER", count=3)]))


def test_proposing_tickets_after_the_question_limit_is_allowed(make_input) -> None:
    exhausted = make_input(
        existing_problems=[
            Problem(ref=EXISTING, summary="확인 불가", category="OTHER", questionCount=3)
        ]
    )

    validate_outcome(
        exhausted,
        outcome([problem(EXISTING, "PROPOSE_TICKETS", count=3)], [proposal(department="CS")]),
    )


def test_unknown_department_code_is_rejected(data) -> None:
    with pytest.raises(OutputRejected):
        validate_outcome(
            data,
            outcome([problem(EXISTING, "PROPOSE_TICKETS", count=1)], [proposal(department="MARS")]),
        )


def test_a_proposal_must_point_at_a_problem_that_needs_tickets(data) -> None:
    with pytest.raises(OutputRejected):
        validate_outcome(data, outcome([problem(EXISTING, "ASK_USER", count=1)], [proposal()]))


def test_a_problem_that_needs_tickets_cannot_have_none(data) -> None:
    with pytest.raises(OutputRejected):
        validate_outcome(data, outcome([problem(EXISTING, "PROPOSE_TICKETS", count=1)], []))


def test_duplicate_proposal_refs_are_rejected(make_input) -> None:
    both = make_input(
        existing_problems=[
            Problem(ref=EXISTING, summary="A", category="NETWORK_ACCESS", questionCount=1),
            Problem(ref="new:b", summary="B", category="NETWORK_ACCESS", questionCount=0),
        ]
    )
    problems = [
        problem(EXISTING, "PROPOSE_TICKETS", count=1),
        problem("new:b", "PROPOSE_TICKETS", count=0),
    ]

    with pytest.raises(OutputRejected):
        validate_outcome(
            both, outcome(problems, [proposal(EXISTING), proposal("new:b", ref="new:work-1")])
        )


def test_evidence_ids_must_be_present_in_the_returned_evidence(make_input) -> None:
    with pytest.raises(OutputRejected):
        validate_outcome(make_input(), outcome([problem(evidence=("msg-404-r1",))]))


def test_proposal_evidence_ids_are_checked_too(data) -> None:
    with pytest.raises(OutputRejected):
        validate_outcome(
            data,
            outcome(
                [problem(EXISTING, "PROPOSE_TICKETS", count=1)],
                [proposal(evidence=("doc-404",))],
            ),
        )


def test_out_of_scope_problem_needs_no_proposal(make_input) -> None:
    validate_outcome(make_input(), outcome([problem(action="OUT_OF_SCOPE")]))
