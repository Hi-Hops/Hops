import json
from pathlib import Path

import pytest

from hops_ai.agents.base import run_analysis
from hops_ai.contracts.agent_1 import IntakeProblem, agent_result_adapter
from hops_ai.contracts.analysis import AnalysisOutcome
from hops_ai.fakes.agents import (
    FakeDiagnosisAgent,
    FakeIntakeAgent,
    FakeKnowledgeAgent,
    FakeTicketAgent,
)
from hops_ai.harness.errors import UnsupportedRoute
from hops_ai.supervisor.graph import Supervisor
from hops_ai.supervisor.router import Router, RuleRouter, problems_for_ticket

FIXTURE = Path(__file__).resolve().parents[1] / "fixtures" / "agent_1" / "intake.json"
EXISTING = "00000000-0000-4000-8000-000000000201"


def intake_result(*actions: str):
    """Intake 결과 fixture에서 문제를 주어진 nextAction 목록으로 바꾼다."""
    result = agent_result_adapter.validate_python(
        json.loads(FIXTURE.read_text(encoding="utf-8"))["result"]
    )
    problems = [
        IntakeProblem(
            ref=f"new:p{index}",
            summary=f"문제 {index}",
            category="NETWORK_ACCESS",
            questionCount=0,
            nextAction=action,
            question="더 알려 주세요." if action == "ASK_USER" else None,
            evidenceIds=[],
        )
        for index, action in enumerate(actions, start=1)
    ]
    return result.model_copy(update={"data": result.data.model_copy(update={"problems": problems})})


def fake_roles():
    return {
        "INTAKE": FakeIntakeAgent(),
        "KNOWLEDGE": FakeKnowledgeAgent(),
        "DIAGNOSIS": FakeDiagnosisAgent(),
        "TICKET": FakeTicketAgent(),
    }


@pytest.mark.parametrize(
    ("actions", "route"),
    [
        (["ASK_USER"], "merge"),
        (["OUT_OF_SCOPE"], "merge"),
        (["ASK_USER", "OUT_OF_SCOPE"], "merge"),
        (["PROPOSE_TICKETS"], "ticket"),
        (["ASK_USER", "PROPOSE_TICKETS"], "ticket"),
    ],
)
async def test_rule_router_follows_the_intake_proposals(actions, route, make_services) -> None:
    assert await RuleRouter().next_after_intake(intake_result(*actions), make_services()) == route


@pytest.mark.parametrize("action", ["SEARCH", "DIAGNOSE"])
async def test_rule_router_refuses_routes_that_are_not_connected_yet(action, make_services) -> None:
    with pytest.raises(UnsupportedRoute):
        await RuleRouter().next_after_intake(
            intake_result("PROPOSE_TICKETS", action), make_services()
        )


def test_only_problems_that_need_tickets_are_sent_on() -> None:
    problems = problems_for_ticket(intake_result("ASK_USER", "PROPOSE_TICKETS", "OUT_OF_SCOPE"))

    assert [problem.ref for problem in problems] == ["new:p2"]


def test_rule_router_satisfies_the_router_interface() -> None:
    assert isinstance(RuleRouter(), Router)


async def test_rule_router_uses_no_model_calls(make_services, make_input, make_message) -> None:
    services = make_services()

    outcome = await Supervisor(fake_roles()).analyze(make_input(), services)

    assert isinstance(outcome, AnalysisOutcome)
    assert services.runtime.budget.llm_calls_used == 0


class ForcedRoute:
    def __init__(self, route: str) -> None:
        self.route = route
        self.seen = []

    async def next_after_intake(self, result, services):
        self.seen.append((result, services))
        return self.route


async def test_supervisor_uses_the_router_it_is_given(make_services, make_input) -> None:
    router = ForcedRoute("merge")
    services = make_services()

    outcome = await Supervisor(fake_roles(), router=router).analyze(make_input(), services)

    assert isinstance(outcome, AnalysisOutcome)
    [(seen_result, seen_services)] = router.seen
    assert seen_result.role == "INTAKE"
    assert seen_services is services


async def test_a_bad_route_cannot_hide_a_problem_that_needs_tickets(
    make_services, make_input, make_message
) -> None:
    from hops_ai.contracts.agent_1 import Problem

    data = make_input(
        messages=[make_message("오류 코드는 809예요.", 603)],
        existing_problems=[
            Problem(
                ref=EXISTING, summary="VPN 접속 오류", category="NETWORK_ACCESS", questionCount=1
            )
        ],
    )  # 규칙 라우터라면 Ticket 역할로 가는 입력이다

    result = await run_analysis(
        Supervisor(fake_roles(), router=ForcedRoute("merge")), data, make_services()
    )

    assert result.error.code == "INVALID_OUTPUT"  # 티켓 제안 없이 끝내면 결과 검증이 막는다


async def test_router_can_send_the_run_to_the_ticket_role(
    make_services, make_input, make_message
) -> None:
    from hops_ai.contracts.agent_1 import Problem

    data = make_input(
        messages=[make_message("오류 코드는 809예요.", 603)],
        existing_problems=[
            Problem(
                ref=EXISTING, summary="VPN 접속 오류", category="NETWORK_ACCESS", questionCount=1
            )
        ],
    )

    outcome = await Supervisor(fake_roles(), router=ForcedRoute("ticket")).analyze(
        data, make_services()
    )

    assert len(outcome.ticket_proposals) == 1


async def test_router_failures_are_reported_like_other_failures(make_services, make_input) -> None:
    class Broken:
        async def next_after_intake(self, result, services):
            raise UnsupportedRoute("라우터가 경로를 정하지 못했다")

    result = await run_analysis(
        Supervisor(fake_roles(), router=Broken()), make_input(), make_services()
    )

    assert result.error.message == "아직 지원하지 않는 처리 경로다"


async def test_a_route_outside_the_transition_table_fails_instead_of_being_followed(
    make_services, make_input
) -> None:
    result = await run_analysis(
        Supervisor(fake_roles(), router=ForcedRoute("diagnosis")), make_input(), make_services()
    )

    assert result.error.code == "INTERNAL_ERROR"
