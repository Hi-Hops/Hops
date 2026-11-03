"""업무 State와 독립적으로 한 실행의 기한·취소·수명을 관리한다."""

import asyncio
import inspect
from collections.abc import Awaitable

from hops_ai.harness.context import RunContext
from hops_ai.harness.errors import DeadlineExceeded, InputRejected, RunCancelled

_cleanup_tasks: set[asyncio.Future] = set()


def _consume_cleanup_result(task: asyncio.Future) -> None:
    """늦게 끝난 취소 정리의 참조와 예외를 수거한다."""
    _cleanup_tasks.discard(task)
    if not task.cancelled():
        task.exception()


class RunExecution:
    """하나의 RunContext를 한 작업에 연결하고 대기 중에도 실행 제한을 강제한다.

    Supervisor나 특정 wire DTO를 알지 않는다. 호출 측은 모델·도구 wrapper에
    ensure_active를 전달하여 종료된 실행에서 후속 호출이 시작되지 않게 한다.
    영속 runId 중복 접수와 결과 저장은 외부 실행 관리자의 책임이다.
    """

    def __init__(self, context: RunContext) -> None:
        """문맥 사용을 예약한다. 이미 다른 실행이 사용한 문맥은 거부한다."""
        context.claim_execution()
        self._context = context
        self._started = False
        self._finished = False

    def ensure_active(self) -> None:
        """취소·전체 기한·누적 한도와 실행 종료 여부를 확인한다."""
        self._context.ensure_active()
        if self._finished:
            raise InputRejected("이미 종료된 실행 문맥이다")
        self._context.budget.check_limits()

    async def run[T](self, operation: Awaitable[T]) -> T:
        """작업 하나를 실행하며 기한·취소가 먼저 오면 늦은 결과를 사용하지 않는다.

        외부 task 취소는 CancelledError 그대로 전달한다. 어댑터가 취소 정리를
        오래 기다려도 이 메서드가 무제한 대기하지 않으며, 늦게 끝난 작업은
        callback으로 수거한다. 원격 요청의 실제 과금 취소 여부와는 구분한다.
        """
        if self._started:
            if inspect.iscoroutine(operation):
                operation.close()
            raise InputRejected("run은 한 번만 호출할 수 있다")
        self._started = True
        try:
            self.ensure_active()
        except BaseException:
            self._finished = True
            if inspect.iscoroutine(operation):
                operation.close()
            raise

        task = asyncio.ensure_future(operation)
        cancel_task = asyncio.create_task(self._context.wait_cancelled())
        try:
            done, _ = await asyncio.wait(
                (task, cancel_task),
                timeout=self._context.budget.remaining_seconds(),
                return_when=asyncio.FIRST_COMPLETED,
            )
            self.ensure_active()
            if cancel_task in done:
                raise RunCancelled
            if task not in done:
                raise DeadlineExceeded
            return task.result()
        finally:
            self._finished = True
            for cleanup in (task, cancel_task):
                cleanup.cancel()
                # 비협조 어댑터의 정리를 기다리며 전체 실행 기한을 늘리지 않는다.
                _cleanup_tasks.add(cleanup)
                cleanup.add_done_callback(_consume_cleanup_result)
            await asyncio.wait((task, cancel_task), timeout=0)
