from datetime import timedelta

import pytest

from hops_ai.harness.context import RunContext
from hops_ai.harness.errors import DeadlineExceeded, RunCancelled


@pytest.fixture
def context(clock, make_settings):
    return RunContext.create(
        run_id="test-run",
        inquiry_id="test-inquiry",
        inquiry_version=1,
        access_scope_ref="test-scope",
        settings=make_settings(),
        clock=clock,
    )


def test_cancelled_run_stops_before_new_work(context) -> None:
    context.cancel()
    with pytest.raises(RunCancelled):
        context.ensure_active()


def test_expired_run_stops_before_new_work(context, clock) -> None:
    clock.advance(60)
    with pytest.raises(DeadlineExceeded):
        context.ensure_active()


def test_cancellation_is_reported_before_deadline(context, clock) -> None:
    context.cancel()
    clock.advance(60)
    with pytest.raises(RunCancelled):
        context.ensure_active()


def test_spring_deadline_cannot_be_extended_between_roles(clock, make_settings) -> None:
    context = RunContext.create(
        run_id="test-run",
        inquiry_id="test-inquiry",
        inquiry_version=1,
        access_scope_ref="test-scope",
        settings=make_settings(),
        clock=clock,
        deadline_at=clock.now() + timedelta(seconds=20),
    )
    clock.advance(15)
    assert context.budget.start_llm_call() == 5
