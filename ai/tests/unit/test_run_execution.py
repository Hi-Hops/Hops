"""업무 State 없이 공통 실행 수명·취소·기한을 검사한다."""

import asyncio

import pytest

from hops_ai.harness.context import RunContext
from hops_ai.harness.errors import DeadlineExceeded, InputRejected, OutputRejected, RunCancelled
from hops_ai.harness.execution import RunExecution


def execution_for(clock, make_settings):
    """서버 설정과 가짜 시계로 독립된 실행 문맥을 준비한다."""
    context = RunContext.create(
        run_id="test-run",
        inquiry_id="test-inquiry",
        inquiry_version=1,
        access_scope_ref="test-scope",
        settings=make_settings(),
        clock=clock,
    )
    return RunExecution(context), context


async def test_completed_execution_returns_value_and_blocks_followup_work(clock, make_settings):
    execution, context = execution_for(clock, make_settings)

    async def operation():
        return "작업 결과"

    assert await execution.run(operation()) == "작업 결과"
    with pytest.raises(InputRejected):
        execution.ensure_active()
    with pytest.raises(InputRejected):
        await execution.run(operation())
    with pytest.raises(InputRejected):
        RunExecution(context)


@pytest.mark.parametrize("expired", [False, True])
async def test_inactive_context_never_starts_operation(clock, make_settings, expired):
    execution, context = execution_for(clock, make_settings)
    if expired:
        clock.advance(61)
    else:
        context.cancel()
    called = False

    async def operation():
        nonlocal called
        called = True

    with pytest.raises(DeadlineExceeded if expired else RunCancelled):
        await execution.run(operation())
    assert not called


@pytest.mark.parametrize(
    "interruption,expected",
    [
        ("cancel", RunCancelled),
        ("deadline", DeadlineExceeded),
        ("external", asyncio.CancelledError),
    ],
)
async def test_slow_cleanup_does_not_delay_termination(
    clock, make_settings, interruption, expected
):
    execution, context = execution_for(clock, make_settings)
    entered = asyncio.Event()
    cleanup_started = asyncio.Event()
    release = asyncio.Event()

    async def waiting():
        entered.set()
        try:
            await asyncio.Event().wait()
        except asyncio.CancelledError:
            cleanup_started.set()
            await release.wait()
            return "늦은 결과"

    if interruption == "deadline":
        context.budget.deadline = clock.monotonic() + 0.001
    worker = asyncio.create_task(waiting())
    task = asyncio.create_task(execution.run(worker))
    await entered.wait()
    if interruption == "cancel":
        context.cancel()
    elif interruption == "external":
        task.cancel()

    try:
        with pytest.raises(expected):
            await asyncio.wait_for(asyncio.shield(task), timeout=1)
        assert cleanup_started.is_set()
        assert not worker.done()
        with pytest.raises((InputRejected, RunCancelled, DeadlineExceeded)):
            execution.ensure_active()
    finally:
        release.set()
        await asyncio.gather(worker, task, return_exceptions=True)


async def test_operation_failure_is_preserved_without_retry(clock, make_settings):
    execution, _ = execution_for(clock, make_settings)
    calls = 0

    async def operation():
        nonlocal calls
        calls += 1
        raise OutputRejected("검증 실패")

    with pytest.raises(OutputRejected, match="검증 실패"):
        await execution.run(operation())
    assert calls == 1
