import itertools

import pytest

from hops_ai.agents.base import failure_result, run_analysis
from hops_ai.contracts.agent_1 import IntakeProblem, IntakeResult
from hops_ai.contracts.analysis import AnalysisFailure, AnalysisOutcome
from hops_ai.fakes.agents import (
    FakeDiagnosisAgent,
    FakeIntakeAgent,
    FakeKnowledgeAgent,
    FakeTicketAgent,
)
from hops_ai.harness.errors import DeadlineExceeded, TransientError
from hops_ai.harness.events import RunEvents
from hops_ai.supervisor import graph as supervisor_graph
from hops_ai.supervisor.graph import GRAPH_RECURSION_LIMIT, Supervisor

EXISTING = "00000000-0000-4000-8000-000000000201"


class Recording:
    """실제 에이전트를 감싸 받은 요청을 기록한다."""

    def __init__(self, inner) -> None:
        self.inner = inner
        self.requests = []

    async def execute(self, request, services):
        self.requests.append(request)
        return await self.inner.execute(request, services)


class Failing:
    def __init__(self, error: BaseException) -> None:
        self.error = error
        self.requests = []

    async def execute(self, request, services):
        self.requests.append(request)
        return failure_result(
            request, services, services.runtime.budget.clock.monotonic(), self.error
        )


class Transforms:
    """실제 에이전트의 결과를 고쳐서 돌려준다."""

    def __init__(self, inner, change) -> None:
        self.inner = inner
        self.change = change

    async def execute(self, request, services):
        return self.change(await self.inner.execute(request, services), services)


def fake_roles(**overrides):
    roles = {
        "INTAKE": Recording(FakeIntakeAgent()),
        "KNOWLEDGE": FakeKnowledgeAgent(),
        "DIAGNOSIS": FakeDiagnosisAgent(),
        "TICKET": Recording(FakeTicketAgent()),
    }
    roles.update(overrides)
    return roles


def counter_ids():
    numbers = itertools.count(1)
    return lambda: f"00000000-0000-4000-8000-{next(numbers):012d}"


def with_existing_problem(make_input, make_message):
    from hops_ai.contracts.agent_1 import Problem

    return make_input(
        messages=[make_message("오류 코드는 809예요.", 603)],
        existing_problems=[
            Problem(
                ref=EXISTING, summary="VPN 접속 오류", category="NETWORK_ACCESS", questionCount=1
            )
        ],
    )


def intake_with_actions(*actions: str):
    """Intake 결과에서 문제를 주어진 nextAction 목록으로 바꾼다."""

    def change(result: IntakeResult, services) -> IntakeResult:
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
        return result.model_copy(
            update={"data": result.data.model_copy(update={"problems": problems})}
        )

    return change


async def test_first_inquiry_asks_and_never_calls_the_ticket_role(
    make_services, make_input
) -> None:
    roles = fake_roles()

    outcome = await Supervisor(roles).analyze(make_input(), make_services())

    assert outcome.problems[0].nextAction == "ASK_USER"
    assert outcome.ticket_proposals == []
    assert len(roles["INTAKE"].requests) == 1
    assert roles["TICKET"].requests == []


async def test_follow_up_goes_through_intake_then_ticket(
    make_services, make_input, make_message
) -> None:
    roles = fake_roles()

    outcome = await Supervisor(roles).analyze(
        with_existing_problem(make_input, make_message), make_services()
    )

    assert [item.departmentCode for item in outcome.ticket_proposals] == ["NETWORK"]
    assert len(roles["TICKET"].requests) == 1
    assert [item.id for item in outcome.evidence] == [outcome.problems[0].evidenceIds[0]]


async def test_only_problems_needing_tickets_are_sent_to_the_ticket_role(
    make_services, make_input
) -> None:
    roles = fake_roles(
        INTAKE=Transforms(FakeIntakeAgent(), intake_with_actions("ASK_USER", "PROPOSE_TICKETS"))
    )
    roles["TICKET"] = Recording(FakeTicketAgent())

    outcome = await Supervisor(roles).analyze(make_input(), make_services())

    [request] = roles["TICKET"].requests
    assert [problem.ref for problem in request.payload.problems] == ["new:p2"]
    assert [item.nextAction for item in outcome.problems] == ["ASK_USER", "PROPOSE_TICKETS"]
    assert [item.problemRef for item in outcome.ticket_proposals] == ["new:p2"]


async def test_out_of_scope_problem_ends_without_tickets(make_services, make_input) -> None:
    roles = fake_roles(INTAKE=Transforms(FakeIntakeAgent(), intake_with_actions("OUT_OF_SCOPE")))
    roles["TICKET"] = Recording(FakeTicketAgent())

    outcome = await Supervisor(roles).analyze(make_input(), make_services())

    assert outcome.problems[0].nextAction == "OUT_OF_SCOPE"
    assert roles["TICKET"].requests == []


@pytest.mark.parametrize("action", ["SEARCH", "DIAGNOSE"])
async def test_routes_not_connected_yet_are_reported_not_silently_skipped(
    action, make_services, make_input
) -> None:
    roles = fake_roles(INTAKE=Transforms(FakeIntakeAgent(), intake_with_actions(action)))
    roles["TICKET"] = Recording(FakeTicketAgent())

    result = await run_analysis(Supervisor(roles), make_input(), make_services())

    assert isinstance(result, AnalysisFailure)
    assert result.error.message == "아직 지원하지 않는 처리 경로다"
    assert roles["TICKET"].requests == []


async def test_failed_role_status_and_error_reach_the_caller_unchanged(
    make_services, make_input
) -> None:
    roles = fake_roles(INTAKE=Failing(TransientError()))
    roles["TICKET"] = Recording(FakeTicketAgent())

    result = await run_analysis(Supervisor(roles), make_input(), make_services())

    assert isinstance(result, AnalysisFailure)
    assert (result.status, result.error.code, result.error.retryable) == (
        "ERROR",
        "MODEL_UNAVAILABLE",
        True,
    )
    assert roles["TICKET"].requests == []


async def test_ticket_role_timeout_is_reported_as_timed_out(
    make_services, make_input, make_message
) -> None:
    roles = fake_roles(TICKET=Failing(DeadlineExceeded()))

    result = await run_analysis(
        Supervisor(roles), with_existing_problem(make_input, make_message), make_services()
    )

    assert isinstance(result, AnalysisFailure)
    assert (result.status, result.error.code) == ("TIMED_OUT", "DEADLINE_EXCEEDED")


async def test_unresolved_ticket_problems_are_not_hidden_behind_success(
    make_services, make_input, make_message
) -> None:
    def leave_unresolved(result, services):
        data = result.data.model_copy(
            update={"ticketProposals": [], "unresolvedProblemRefs": [EXISTING]}
        )
        return result.model_copy(update={"data": data})

    roles = fake_roles(TICKET=Transforms(FakeTicketAgent(), leave_unresolved))

    result = await run_analysis(
        Supervisor(roles), with_existing_problem(make_input, make_message), make_services()
    )

    assert isinstance(result, AnalysisFailure)
    assert result.error.code == "INVALID_OUTPUT"


async def test_cancelled_run_stops_before_calling_any_role(make_services, make_input) -> None:
    roles = fake_roles()
    services = make_services()
    services.runtime.cancel()

    result = await run_analysis(Supervisor(roles), make_input(), services)

    assert isinstance(result, AnalysisFailure)
    assert (result.status, result.error.code) == ("CANCELLED", "SUPERSEDED")
    assert roles["INTAKE"].requests == []


async def test_deadline_passing_between_roles_stops_the_next_role(
    make_services, make_input, make_message, clock
) -> None:
    def use_up_the_deadline(result, services):
        clock.advance(60)
        return result

    roles = fake_roles(INTAKE=Transforms(FakeIntakeAgent(), use_up_the_deadline))
    roles["TICKET"] = Recording(FakeTicketAgent())

    result = await run_analysis(
        Supervisor(roles), with_existing_problem(make_input, make_message), make_services()
    )

    assert isinstance(result, AnalysisFailure)
    assert result.status == "TIMED_OUT"
    assert roles["TICKET"].requests == []


async def test_roles_share_one_deadline_and_get_distinct_invocation_ids(
    make_services, make_input, make_message, clock
) -> None:
    def pass_some_time(result, services):
        clock.advance(10)
        return result

    roles = fake_roles(INTAKE=Recording(Transforms(FakeIntakeAgent(), pass_some_time)))
    roles["TICKET"] = Recording(FakeTicketAgent())
    data = with_existing_problem(make_input, make_message)

    await Supervisor(roles, new_id=counter_ids()).analyze(data, make_services())

    [intake_request] = roles["INTAKE"].requests
    [ticket_request] = roles["TICKET"].requests
    assert intake_request.invocationId != ticket_request.invocationId
    assert intake_request.runId == ticket_request.runId == data.run_id
    assert intake_request.context.deadlineAt == ticket_request.context.deadlineAt


async def test_progress_events_are_sent_for_each_role_that_runs(
    make_services, make_input, make_message
) -> None:
    events = RunEvents(
        run_id="00000000-0000-4000-8000-000000000001",
        inquiry_id="00000000-0000-4000-8000-000000000003",
        inquiry_version=1,
    )
    data = with_existing_problem(make_input, make_message)

    await Supervisor(fake_roles(), events=events).analyze(data, make_services())

    steps = [(event.payload.stepType, event.payload.status) for _, event in events.history()]
    assert steps == [
        ("INTAKE", "RUNNING"),
        ("INTAKE", "DONE"),
        ("TICKET", "RUNNING"),
        ("TICKET", "DONE"),
    ]


async def test_no_ticket_progress_when_the_ticket_role_is_not_needed(
    make_services, make_input
) -> None:
    events = RunEvents(
        run_id="00000000-0000-4000-8000-000000000001",
        inquiry_id="00000000-0000-4000-8000-000000000003",
        inquiry_version=1,
    )

    await Supervisor(fake_roles(), events=events).analyze(make_input(), make_services())

    assert [event.payload.stepType for _, event in events.history()] == ["INTAKE", "INTAKE"]


async def test_missing_role_registration_is_reported(make_services, make_input) -> None:
    roles = fake_roles()
    del roles["INTAKE"]

    result = await run_analysis(Supervisor(roles), make_input(), make_services())

    assert isinstance(result, AnalysisFailure)
    assert result.error.code == "INVALID_OUTPUT"


async def test_result_with_a_foreign_invocation_id_is_rejected(make_services, make_input) -> None:
    def wrong_invocation(result, services):
        return result.model_copy(update={"invocationId": "00000000-0000-4000-8000-000000000999"})

    roles = fake_roles(INTAKE=Transforms(FakeIntakeAgent(), wrong_invocation))

    result = await run_analysis(Supervisor(roles), make_input(), make_services())

    assert isinstance(result, AnalysisFailure)
    assert result.error.code == "INVALID_OUTPUT"


async def test_graph_step_limit_is_applied(monkeypatch, make_services, make_input) -> None:
    assert GRAPH_RECURSION_LIMIT == 25
    monkeypatch.setattr(supervisor_graph, "GRAPH_RECURSION_LIMIT", 1)

    result = await run_analysis(Supervisor(fake_roles()), make_input(), make_services())

    assert isinstance(result, AnalysisFailure)  # 한 단계만 허용하면 그래프가 끝까지 가지 못한다
    assert not isinstance(result, AnalysisOutcome)
