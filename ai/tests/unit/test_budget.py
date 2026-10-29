from datetime import timedelta

import pytest

from hops_ai.harness.budget import RunBudget
from hops_ai.harness.errors import DeadlineExceeded, LlmCallLimitExceeded


def test_budget_uses_settings_window_when_spring_gives_no_deadline(clock, make_settings) -> None:
    budget = RunBudget.start(make_settings(), clock=clock)

    assert budget.remaining_seconds() == 60
    assert budget.max_llm_calls == 8
    assert budget.max_auto_retries == 2
    assert budget.max_output_tokens == 1500


def test_earlier_spring_deadline_wins(clock, make_settings) -> None:
    deadline_at = clock.now() + timedelta(seconds=20)

    budget = RunBudget.start(make_settings(), deadline_at=deadline_at, clock=clock)

    assert budget.remaining_seconds() == 20


def test_later_spring_deadline_does_not_extend_the_server_limit(clock, make_settings) -> None:
    deadline_at = clock.now() + timedelta(seconds=300)

    budget = RunBudget.start(make_settings(), deadline_at=deadline_at, clock=clock)

    assert budget.remaining_seconds() == 60


def test_deadline_already_passed_is_exceeded_immediately(clock, make_settings) -> None:
    deadline_at = clock.now() - timedelta(seconds=1)
    budget = RunBudget.start(make_settings(), deadline_at=deadline_at, clock=clock)

    assert budget.remaining_seconds() == 0
    with pytest.raises(DeadlineExceeded):
        budget.check_deadline()


def test_remaining_time_decreases_as_time_passes(clock, make_settings) -> None:
    budget = RunBudget.start(make_settings(), clock=clock)

    clock.advance(25)

    assert budget.remaining_seconds() == 35


def test_single_and_multi_agent_get_the_same_call_limit(clock, make_settings) -> None:
    single = RunBudget.start(make_settings(agent_mode="single"), clock=clock)
    multi = RunBudget.start(make_settings(agent_mode="multi"), clock=clock)

    assert single.max_llm_calls == multi.max_llm_calls == 8


def test_calls_beyond_the_limit_are_refused(clock, make_settings) -> None:
    budget = RunBudget.start(make_settings(max_llm_calls=3), clock=clock)

    for _ in range(3):
        budget.start_llm_call()

    with pytest.raises(LlmCallLimitExceeded):
        budget.start_llm_call()
    assert budget.llm_calls_used == 3


def test_default_call_limit_is_eight(clock, make_settings) -> None:
    budget = RunBudget.start(make_settings(), clock=clock)

    for _ in range(8):
        budget.start_llm_call()

    with pytest.raises(LlmCallLimitExceeded):
        budget.start_llm_call()


def test_call_timeout_is_capped_by_remaining_time(clock, make_settings) -> None:
    budget = RunBudget.start(make_settings(), clock=clock)

    assert budget.start_llm_call() == 30

    clock.advance(50)

    assert budget.start_llm_call() == 10


def test_no_call_starts_after_the_deadline(clock, make_settings) -> None:
    budget = RunBudget.start(make_settings(), clock=clock)

    clock.advance(60)

    with pytest.raises(DeadlineExceeded):
        budget.start_llm_call()
    assert budget.llm_calls_used == 0


def test_deadline_is_checked_before_the_call_limit(clock, make_settings) -> None:
    budget = RunBudget.start(make_settings(max_llm_calls=1), clock=clock)
    budget.start_llm_call()

    clock.advance(60)

    with pytest.raises(DeadlineExceeded):
        budget.start_llm_call()


def test_roles_share_one_budget_so_time_is_not_reset_per_role(clock, make_settings) -> None:
    budget = RunBudget.start(make_settings(agent_mode="multi"), clock=clock)

    clock.advance(40)  # 첫 역할이 40초를 썼다
    second_role_timeout = budget.start_llm_call()

    assert second_role_timeout == 20


def test_effective_deadline_is_now_plus_remaining(clock, make_settings) -> None:
    budget = RunBudget.start(make_settings(), clock=clock)
    clock.advance(15)

    assert budget.effective_deadline_at() == clock.now() + timedelta(seconds=45)
