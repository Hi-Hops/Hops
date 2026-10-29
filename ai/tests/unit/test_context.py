import copy
import json
from datetime import UTC, datetime, timedelta, timezone
from pathlib import Path

import pytest

from hops_ai.contracts.agent_1 import Actor, agent_request_adapter
from hops_ai.harness.context import RunContext, format_utc
from hops_ai.harness.errors import DeadlineExceeded, RunCancelled
from hops_ai.harness.policy import ToolPolicy

INTAKE_FIXTURE = Path(__file__).resolve().parents[1] / "fixtures" / "agent_1" / "intake.json"

ACTOR = Actor(
    userId="00000000-0000-4000-8000-000000000100",
    departmentId="00000000-0000-4000-8000-000000000018",
    roles=[],
)


@pytest.fixture
def context(clock, make_settings) -> RunContext:
    return RunContext.create(
        run_id="00000000-0000-4000-8000-000000000001",
        inquiry_id="00000000-0000-4000-8000-000000000003",
        inquiry_version=1,
        actor=ACTOR,
        access_scope_ref="mock:owner:inquiry-3",
        settings=make_settings(),
        tool_policy=ToolPolicy.of({"DIAGNOSIS": ["read_service_status"]}),
        clock=clock,
    )


def test_new_context_is_active(context: RunContext) -> None:
    context.ensure_active()

    assert not context.is_cancelled


def test_cancelled_run_stops_at_the_next_check(context: RunContext) -> None:
    context.cancel()

    assert context.is_cancelled
    with pytest.raises(RunCancelled):
        context.ensure_active()


def test_expired_deadline_stops_at_the_next_check(context: RunContext, clock) -> None:
    clock.advance(60)

    with pytest.raises(DeadlineExceeded):
        context.ensure_active()


def test_cancellation_is_reported_before_the_deadline(context: RunContext, clock) -> None:
    context.cancel()
    clock.advance(60)

    with pytest.raises(RunCancelled):
        context.ensure_active()


def test_context_uses_the_earlier_of_spring_deadline_and_server_limit(clock, make_settings) -> None:
    context = RunContext.create(
        run_id="00000000-0000-4000-8000-000000000001",
        inquiry_id="00000000-0000-4000-8000-000000000003",
        inquiry_version=1,
        actor=ACTOR,
        access_scope_ref="mock:owner:inquiry-3",
        settings=make_settings(),
        deadline_at=clock.now() + timedelta(seconds=20),
        clock=clock,
    )

    assert context.budget.remaining_seconds() == 20


def test_agent_context_carries_role_tools_and_the_shared_deadline(context: RunContext) -> None:
    diagnosis = context.agent_context("DIAGNOSIS")
    intake = context.agent_context("INTAKE")

    assert diagnosis.allowedTools == ["read_service_status"]
    assert intake.allowedTools == []
    assert diagnosis.deadlineAt == "2026-10-28T00:01:00Z"
    assert diagnosis.actor == ACTOR
    assert diagnosis.accessScopeRef == "mock:owner:inquiry-3"


def test_deadline_in_agent_context_does_not_move_when_a_later_role_starts(
    context: RunContext, clock
) -> None:
    first = context.agent_context("INTAKE").deadlineAt

    clock.advance(40)
    second = context.agent_context("DIAGNOSIS").deadlineAt

    assert first == second == "2026-10-28T00:01:00Z"


def test_agent_context_can_be_placed_in_an_agent_1_request(context: RunContext) -> None:
    request = copy.deepcopy(json.loads(INTAKE_FIXTURE.read_text(encoding="utf-8"))["request"])
    request["context"] = context.agent_context("INTAKE").model_dump(mode="json")

    agent_request_adapter.validate_python(request)


def test_format_utc_converts_to_the_contract_notation() -> None:
    kst = timezone(timedelta(hours=9))

    assert format_utc(datetime(2026, 10, 28, 9, 1, 5, 123456, tzinfo=kst)) == "2026-10-28T00:01:05Z"
    assert format_utc(datetime(2026, 10, 28, 0, 0, 0, tzinfo=UTC)) == "2026-10-28T00:00:00Z"
