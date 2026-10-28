import json
from pathlib import Path

import pytest

from hops_ai.agents.base import Agent, Analyzer, execute_safely
from hops_ai.contracts.agent_1 import Problem, agent_request_adapter, agent_result_adapter
from hops_ai.fakes.agents import (
    ASK_QUESTION,
    TICKET_ANSWER,
    FakeBaselineAgent,
    FakeDiagnosisAgent,
    FakeIntakeAgent,
    FakeKnowledgeAgent,
    FakeTicketAgent,
    fake_intake,
    fake_ticket_proposals,
    guess_category,
    message_evidence,
)

FIXTURES = Path(__file__).resolve().parents[1] / "fixtures" / "agent_1"


def request_of(role: str):
    data = json.loads((FIXTURES / f"{role}.json").read_text(encoding="utf-8"))
    return agent_request_adapter.validate_python(data["request"])


def problem(ref="00000000-0000-4000-8000-000000000201", category="NETWORK_ACCESS", count=1):
    return Problem(ref=ref, summary="VPN 접속 오류", category=category, questionCount=count)


@pytest.mark.parametrize(
    ("text", "category"),
    [
        ("VPN 접속 오류가 나요", "NETWORK_ACCESS"),
        ("vpn 안 돼요", "NETWORK_ACCESS"),
        ("비밀번호를 잊었어요", "ACCOUNT_AUTH"),
        ("로그인이 안 돼요", "ACCOUNT_AUTH"),
        ("접근 권한이 필요해요", "PERMISSION"),
        ("프린터 토너가 없어요", "OTHER"),
    ],
)
def test_category_is_guessed_from_keywords(text: str, category: str) -> None:
    assert guess_category(text) == category


def test_first_inquiry_asks_the_user_for_more_information(make_message) -> None:
    answer, problems, evidence = fake_intake([make_message("VPN 접속 오류가 나요.")], [])

    assert answer == ASK_QUESTION
    [item] = problems
    assert (item.ref, item.category, item.nextAction) == ("new:p1", "NETWORK_ACCESS", "ASK_USER")
    assert item.question == ASK_QUESTION
    assert item.questionCount == 0
    assert item.evidenceIds == [evidence[0].id]


def test_follow_up_proposes_tickets_for_the_existing_problems(make_message) -> None:
    existing = [problem(count=1)]

    answer, problems, _ = fake_intake([make_message("오류 코드는 809예요.", 603)], existing)

    assert answer == TICKET_ANSWER
    [item] = problems
    assert (item.ref, item.nextAction, item.question) == (existing[0].ref, "PROPOSE_TICKETS", None)
    assert item.questionCount == 1  # 질문 횟수는 서버 값을 그대로 둔다


def test_no_user_message_gives_an_empty_result() -> None:
    assert fake_intake([], []) == ("", [], [])


def test_message_evidence_points_at_the_message_and_its_revision(make_message) -> None:
    evidence = message_evidence(make_message("내용", number=601, revision=2))

    assert (evidence.kind, evidence.sourceVersion) == ("MESSAGE", "2")
    assert evidence.sourceId == "00000000-0000-4000-8000-000000000601"
    assert evidence.observedAt is None


@pytest.mark.parametrize(
    ("category", "department"),
    [
        ("NETWORK_ACCESS", "NETWORK"),
        ("ACCOUNT_AUTH", "ACCOUNT_PERMISSION"),
        ("PERMISSION", "ACCOUNT_PERMISSION"),
        ("BUSINESS_SERVICE_ERROR", "BACKEND"),
        ("SOFTWARE", "SYSTEM"),
        ("OTHER", "CS"),
    ],
)
def test_proposal_goes_to_the_department_matching_the_category(
    category, department, departments
) -> None:
    [proposal] = fake_ticket_proposals([problem(category=category)], departments, [], ["e-1"])

    assert proposal.departmentCode == department
    assert proposal.purpose == "INCIDENT"


def test_unknown_category_department_falls_back_to_cs(departments) -> None:
    only_cs = [department for department in departments if department.code == "CS"]

    [proposal] = fake_ticket_proposals([problem(category="NETWORK_ACCESS")], only_cs, [], [])

    assert proposal.departmentCode == "CS"


def test_cs_review_problem_goes_to_cs_with_cs_review_purpose(departments) -> None:
    target = problem(category="NETWORK_ACCESS")

    [proposal] = fake_ticket_proposals([target], departments, [target.ref], [])

    assert (proposal.departmentCode, proposal.purpose) == ("CS", "CS_REVIEW")


def test_proposals_have_sequential_refs_and_carry_the_evidence(departments) -> None:
    problems = [problem(ref="new:a"), problem(ref="new:b")]

    proposals = fake_ticket_proposals(problems, departments, [], ["e-1", "e-2"])

    assert [item.ref for item in proposals] == ["new:work-1", "new:work-2"]
    assert [item.problemRef for item in proposals] == ["new:a", "new:b"]
    assert all(item.evidenceIds == ["e-1", "e-2"] for item in proposals)


@pytest.mark.parametrize(
    ("role", "agent"),
    [
        ("intake", FakeIntakeAgent()),
        ("knowledge", FakeKnowledgeAgent()),
        ("diagnosis", FakeDiagnosisAgent()),
        ("ticket", FakeTicketAgent()),
    ],
)
async def test_fake_role_results_satisfy_the_contract(role, agent, make_services) -> None:
    request = request_of(role)

    result = await execute_safely(agent, request, make_services())

    assert result.status == "OK"
    assert result.role == role.upper()
    assert result.meta.modelId is None  # 모델을 쓰지 않았다
    assert agent_result_adapter.validate_python(result.model_dump(mode="json")) == result


async def test_fake_ticket_agent_marks_cs_review_problems(make_services) -> None:
    request = request_of("ticket")  # 두 번째 문제가 CS 검토 대상이다

    result = await FakeTicketAgent().execute(request, make_services())

    assert [item.purpose for item in result.data.ticketProposals] == ["INCIDENT", "CS_REVIEW"]
    assert [item.departmentCode for item in result.data.ticketProposals] == ["NETWORK", "CS"]


async def test_fake_baseline_gives_the_same_outcome_as_fake_intake_plus_ticket(
    make_services, make_input, make_message
) -> None:
    data = make_input(
        messages=[make_message("오류 코드는 809예요.", 603)], existing_problems=[problem()]
    )

    outcome = await FakeBaselineAgent().analyze(data, make_services())

    assert outcome.answer == TICKET_ANSWER
    assert [item.departmentCode for item in outcome.ticket_proposals] == ["NETWORK"]
    assert [item.id for item in outcome.evidence] == [outcome.problems[0].evidenceIds[0]]


async def test_fake_baseline_does_not_propose_tickets_while_asking(
    make_services, make_input
) -> None:
    outcome = await FakeBaselineAgent().analyze(make_input(), make_services())

    assert outcome.answer == ASK_QUESTION
    assert outcome.ticket_proposals == []


def test_fakes_satisfy_the_interfaces() -> None:
    for agent in (FakeIntakeAgent(), FakeKnowledgeAgent(), FakeDiagnosisAgent(), FakeTicketAgent()):
        assert isinstance(agent, Agent)
    assert isinstance(FakeBaselineAgent(), Analyzer)
