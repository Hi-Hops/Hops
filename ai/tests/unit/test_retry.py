import asyncio
from typing import Any

import pytest

from hops_ai.harness.budget import RunBudget
from hops_ai.harness.errors import (
    CallTimeoutError,
    DeadlineExceeded,
    LlmCallLimitExceeded,
    PolicyDenied,
    TransientError,
)
from hops_ai.harness.retry import backoff_delay, call_with_retry


class Sleeper:
    """실제로 기다리지 않고 요청된 대기 시간을 기록하며 가짜 시계만 움직인다."""

    def __init__(self, clock) -> None:
        self.clock = clock
        self.delays: list[float] = []

    async def __call__(self, delay: float) -> None:
        self.delays.append(delay)
        self.clock.advance(delay)


class Script:
    """호출할 때마다 정해 둔 결과를 차례로 돌려주거나 예외를 낸다."""

    def __init__(self, *outcomes: Any) -> None:
        self.outcomes = list(outcomes)
        self.calls = 0

    async def __call__(self) -> Any:
        outcome = self.outcomes[self.calls]
        self.calls += 1
        if isinstance(outcome, BaseException):
            raise outcome
        return outcome


@pytest.fixture
def sleeper(clock) -> Sleeper:
    return Sleeper(clock)


def budget_for(clock, make_settings, **overrides: Any) -> RunBudget:
    return RunBudget.start(make_settings(**overrides), clock=clock)


async def test_success_on_first_attempt_uses_one_call(clock, make_settings, sleeper) -> None:
    budget = budget_for(clock, make_settings)
    operation = Script("ok")

    result = await call_with_retry(operation, budget, sleep=sleeper)

    assert result == "ok"
    assert operation.calls == 1
    assert budget.llm_calls_used == 1
    assert sleeper.delays == []


async def test_transient_error_is_retried_until_success(clock, make_settings, sleeper) -> None:
    budget = budget_for(clock, make_settings)
    operation = Script(TransientError(), "ok")

    result = await call_with_retry(operation, budget, sleep=sleeper, rng=lambda: 1.0)

    assert result == "ok"
    assert operation.calls == 2
    assert budget.llm_calls_used == 2
    assert sleeper.delays == [0.5]


async def test_gives_up_after_the_configured_number_of_retries(
    clock, make_settings, sleeper
) -> None:
    budget = budget_for(clock, make_settings, max_auto_retries=2)
    operation = Script(TransientError("1"), TransientError("2"), TransientError("3"), "ok")

    with pytest.raises(TransientError, match="3"):
        await call_with_retry(operation, budget, sleep=sleeper, rng=lambda: 1.0)

    assert operation.calls == 3  # 최초 1회 + 재시도 2회
    assert budget.llm_calls_used == 3
    assert sleeper.delays == [0.5, 1.0]


async def test_zero_retries_means_no_retry(clock, make_settings, sleeper) -> None:
    budget = budget_for(clock, make_settings, max_auto_retries=0)
    operation = Script(TransientError(), "ok")

    with pytest.raises(TransientError):
        await call_with_retry(operation, budget, sleep=sleeper)

    assert operation.calls == 1
    assert sleeper.delays == []


async def test_retries_count_toward_the_call_limit(clock, make_settings, sleeper) -> None:
    budget = budget_for(clock, make_settings, max_llm_calls=2, max_auto_retries=2)
    operation = Script(TransientError(), TransientError(), "ok")

    with pytest.raises(LlmCallLimitExceeded):
        await call_with_retry(operation, budget, sleep=sleeper)

    assert operation.calls == 2  # 재시도가 남았어도 호출 상한이 먼저 막는다
    assert budget.llm_calls_used == 2


async def test_non_transient_error_is_not_retried(clock, make_settings, sleeper) -> None:
    budget = budget_for(clock, make_settings)
    operation = Script(ValueError("형식 오류"), "ok")

    with pytest.raises(ValueError):
        await call_with_retry(operation, budget, sleep=sleeper)

    assert operation.calls == 1
    assert sleeper.delays == []


async def test_policy_denied_is_never_retried(clock, make_settings, sleeper) -> None:
    budget = budget_for(clock, make_settings)
    operation = Script(PolicyDenied(), "ok")

    with pytest.raises(PolicyDenied):
        await call_with_retry(operation, budget, sleep=sleeper)

    assert operation.calls == 1


async def test_cancellation_is_not_swallowed_or_retried(clock, make_settings, sleeper) -> None:
    budget = budget_for(clock, make_settings)
    operation = Script(asyncio.CancelledError(), "ok")

    with pytest.raises(asyncio.CancelledError):
        await call_with_retry(operation, budget, sleep=sleeper)

    assert operation.calls == 1


async def test_server_retry_after_is_honored(clock, make_settings, sleeper) -> None:
    budget = budget_for(clock, make_settings)
    operation = Script(TransientError(retry_after=3.0), "ok")

    await call_with_retry(operation, budget, sleep=sleeper, rng=lambda: 0.0)

    assert sleeper.delays == [3.0]


async def test_does_not_wait_when_remaining_time_is_shorter_than_the_delay(
    clock, make_settings, sleeper
) -> None:
    budget = budget_for(clock, make_settings)
    operation = Script(TransientError(retry_after=100.0), "ok")

    with pytest.raises(TransientError):
        await call_with_retry(operation, budget, sleep=sleeper)

    assert operation.calls == 1
    assert sleeper.delays == []


async def test_deadline_passing_during_a_call_reports_deadline_not_the_call_error(
    clock, make_settings, sleeper
) -> None:
    budget = budget_for(clock, make_settings)

    async def slow_then_fail() -> None:
        clock.advance(60)
        raise TransientError

    with pytest.raises(DeadlineExceeded):
        await call_with_retry(slow_then_fail, budget, sleep=sleeper)


async def test_each_attempt_is_bounded_by_the_call_timeout(clock, sleeper) -> None:
    budget = RunBudget(
        clock=clock,
        deadline=clock.monotonic() + 60,
        max_llm_calls=8,
        max_auto_retries=0,
        llm_call_timeout=0.01,
        max_output_tokens=1500,
    )

    async def hangs() -> None:
        await asyncio.sleep(5)

    with pytest.raises(CallTimeoutError):
        await call_with_retry(hangs, budget, sleep=sleeper)


async def test_timed_out_call_is_retried_as_a_transient_error(clock, sleeper) -> None:
    budget = RunBudget(
        clock=clock,
        deadline=clock.monotonic() + 60,
        max_llm_calls=8,
        max_auto_retries=1,
        llm_call_timeout=0.01,
        max_output_tokens=1500,
    )
    calls = 0

    async def hangs_once() -> str:
        nonlocal calls
        calls += 1
        if calls == 1:
            await asyncio.sleep(5)
        return "ok"

    result = await call_with_retry(hangs_once, budget, sleep=sleeper)

    assert result == "ok"
    assert calls == 2


def test_backoff_grows_exponentially_up_to_the_cap() -> None:
    delays = [backoff_delay(index, rng=lambda: 1.0) for index in range(6)]

    assert delays == [0.5, 1.0, 2.0, 4.0, 8.0, 8.0]


def test_backoff_applies_full_jitter() -> None:
    assert backoff_delay(2, rng=lambda: 0.0) == 0.0
    assert backoff_delay(2, rng=lambda: 0.5) == 1.0
