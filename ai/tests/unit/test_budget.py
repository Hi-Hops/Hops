from datetime import timedelta

import pytest

from hops_ai.harness.budget import RunBudget
from hops_ai.harness.errors import (
    BudgetExceeded,
    DeadlineExceeded,
    LlmCallLimitExceeded,
    OutputRejected,
)


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
    budget = RunBudget.start(make_settings(), clock=clock)

    clock.advance(40)  # 첫 역할이 40초를 썼다
    second_role_timeout = budget.start_llm_call()

    assert second_role_timeout == 20


def test_effective_deadline_is_now_plus_remaining(clock, make_settings) -> None:
    budget = RunBudget.start(make_settings(), clock=clock)
    clock.advance(15)

    assert budget.effective_deadline_at() == clock.now() + timedelta(seconds=45)


def constrain_budget(budget: RunBudget, **changes) -> None:
    """공통 하네스 검사에 사용할 작은 실행 전체 상한을 명시한다."""
    limits = {
        "max_model_calls": 4,
        "max_input_tokens": 20,
        "max_total_output_tokens": 10,
        "max_tool_calls": 2,
        "max_total_retries": 1,
        "deadline_at": budget.clock.now() + timedelta(seconds=30),
    }
    budget.constrain(**(limits | changes))


def test_constrain_only_tightens_limits_and_keeps_recorded_usage(clock, make_settings) -> None:
    budget = RunBudget.start(make_settings(), clock=clock)
    budget.start_llm_call()
    budget.record_tokens(input_tokens=3, output_tokens=2)
    constrain_budget(budget)
    constrain_budget(
        budget,
        max_model_calls=8,
        max_input_tokens=100,
        max_total_output_tokens=50,
        max_tool_calls=10,
        max_total_retries=5,
        deadline_at=clock.now() + timedelta(seconds=100),
    )
    assert budget.remaining_seconds() == 30
    assert budget.max_llm_calls == 4
    assert budget.max_input_tokens == 20
    assert budget.max_total_output_tokens == 10
    assert budget.max_tool_calls == 2
    assert budget.max_total_retries == 1
    assert (budget.llm_calls_used, budget.input_tokens_used, budget.output_tokens_used) == (1, 3, 2)


def test_invalid_constrain_does_not_partially_change_limits(clock, make_settings) -> None:
    budget = RunBudget.start(make_settings(), clock=clock)
    with pytest.raises(OutputRejected):
        constrain_budget(budget, max_model_calls=1, max_total_retries=True)
    assert budget.max_llm_calls == 8
    assert budget.max_input_tokens is None
    assert budget.remaining_seconds() == 60


def test_constrain_rejects_naive_deadline(clock, make_settings) -> None:
    budget = RunBudget.start(make_settings(), clock=clock)
    with pytest.raises(OutputRejected):
        constrain_budget(budget, deadline_at=clock.now().replace(tzinfo=None))
    assert budget.max_input_tokens is None


def test_output_tokens_respect_per_call_and_remaining_total_limits(clock, make_settings) -> None:
    budget = RunBudget.start(make_settings(max_output_tokens=8), clock=clock)
    constrain_budget(budget)
    assert budget.limit_output_tokens(100) == 8
    budget.record_tokens(input_tokens=1, output_tokens=7)
    assert budget.limit_output_tokens(100) == 3
    budget.record_tokens(input_tokens=1, output_tokens=3)
    with pytest.raises(BudgetExceeded):
        budget.limit_output_tokens(1)


def test_exhausted_input_tokens_prevent_another_model_request(clock, make_settings) -> None:
    budget = RunBudget.start(make_settings(), clock=clock)
    constrain_budget(budget)
    budget.record_tokens(input_tokens=20, output_tokens=1)
    with pytest.raises(BudgetExceeded):
        budget.limit_output_tokens(1)
    assert budget.llm_calls_used == 0


def test_reported_token_overflow_is_kept_before_rejecting_result(clock, make_settings) -> None:
    budget = RunBudget.start(make_settings(), clock=clock)
    constrain_budget(budget)
    with pytest.raises(BudgetExceeded):
        budget.record_tokens(input_tokens=21, output_tokens=2)
    assert (budget.input_tokens_used, budget.output_tokens_used) == (21, 2)


@pytest.mark.parametrize("invalid", [True, -1, 1.0, "1"])
def test_invalid_token_report_does_not_partially_increment_usage(
    clock, make_settings, invalid
) -> None:
    budget = RunBudget.start(make_settings(), clock=clock)
    with pytest.raises(OutputRejected):
        budget.record_tokens(input_tokens=2, output_tokens=invalid)
    assert budget.input_tokens_used == budget.output_tokens_used == 0


def test_tool_reservation_stops_before_exceeding_total_limit(clock, make_settings) -> None:
    budget = RunBudget.start(make_settings(), clock=clock)
    constrain_budget(budget)
    budget.start_tool_call()
    budget.start_tool_call()
    with pytest.raises(BudgetExceeded):
        budget.start_tool_call()
    assert budget.tool_calls_used == 2


def test_retry_reservation_counts_toward_calls_and_shared_retries(clock, make_settings) -> None:
    budget = RunBudget.start(make_settings(), clock=clock)
    constrain_budget(budget)
    budget.start_llm_call()
    budget.start_llm_call(is_retry=True)
    with pytest.raises(BudgetExceeded):
        budget.start_llm_call(is_retry=True)
    assert (budget.llm_calls_used, budget.retries_used) == (2, 1)


@pytest.mark.parametrize(
    "field",
    [
        "llm_calls_used",
        "input_tokens_used",
        "output_tokens_used",
        "tool_calls_used",
        "retries_used",
    ],
)
def test_invalid_internal_counter_is_rejected(clock, make_settings, field) -> None:
    budget = RunBudget.start(make_settings(), clock=clock)
    setattr(budget, field, True)
    with pytest.raises(OutputRejected):
        budget.check_limits()
