"""실행 취소 전파. 진행 중·시작 전·종료 후·반복 취소와 늦은 결과 차단을 확인한다.

모델·DB는 호출하지 않는다.
"""

import asyncio
import logging
from uuid import UUID, uuid4

from hops_ai.harness.errors import RunCancelled
from hops_ai.services.runs.events import RunEventHub
from tests.doubles.run_requests import succeeded
from tests.unit.test_run_executor import eventually, finished, make_executor, request


class CancellablePipeline:
    """하네스처럼 취소 신호를 기다리다 RunCancelled로 끝난다."""

    def __init__(self) -> None:
        self.started = asyncio.Event()
        self.calls = 0

    async def run(self, req, context):
        self.calls += 1
        self.started.set()
        await context.wait_cancelled()
        raise RunCancelled


class StubbornPipeline:
    """취소를 무시하고 stop이 켜질 때까지 계속 실행한다."""

    def __init__(self) -> None:
        self.started = asyncio.Event()
        self.stop = asyncio.Event()

    async def run(self, req, context):
        self.started.set()
        while not self.stop.is_set():
            try:
                await self.stop.wait()
            except asyncio.CancelledError:
                continue
        return succeeded(req)


class LateSuccessPipeline:
    """취소 뒤에도 성공 결과를 돌려준다(취소를 확인하지 않는 늦은 모델 응답)."""

    def __init__(self) -> None:
        self.started = asyncio.Event()
        self.release = asyncio.Event()

    async def run(self, req, context):
        self.started.set()
        await self.release.wait()
        return succeeded(req)


def assert_superseded(result: dict) -> None:
    assert result["status"] == "SUPERSEDED"
    assert result["error"]["code"] == "SUPERSEDED"
    assert result["error"]["retryable"] is False
    assert result["answer"] is None and result["questions"] == []


async def test_cancel_during_pipeline_stores_superseded(caplog) -> None:
    pipeline = CancellablePipeline()
    executor, ledger = make_executor(pipeline)
    req = request()
    await executor.submit(req)
    await asyncio.wait_for(pipeline.started.wait(), 1)

    with caplog.at_level(logging.INFO, logger="hops_ai.services.runs.executor"):
        assert executor.cancel(UUID(req.runId)) is True
        assert_superseded(await finished(ledger, req.runId))
        await eventually(lambda: executor.active_runs == 0)

    # 요청된 취소는 실패 경고로 남기지 않는다.
    assert not [r for r in caplog.records if r.levelno >= logging.WARNING]


async def test_cancel_before_start_skips_the_pipeline() -> None:
    pipeline = CancellablePipeline()
    executor, ledger = make_executor(pipeline)
    req = request()

    await executor.submit(req)
    # 실행 작업이 아직 한 번도 돌지 않은 시점(접수 직후)에 취소한다.
    assert executor.cancel(UUID(req.runId)) is True

    assert_superseded(await finished(ledger, req.runId))
    assert pipeline.calls == 0


async def test_result_arriving_after_cancel_is_not_stored_as_success() -> None:
    pipeline = LateSuccessPipeline()
    executor, ledger = make_executor(pipeline)
    # 늦은 결과가 유예 시간 안에 도착하도록 유예를 넉넉히 둔다.
    executor._cancel_grace_seconds = 1.0
    req = request()
    await executor.submit(req)
    await asyncio.wait_for(pipeline.started.wait(), 1)

    executor.cancel(UUID(req.runId))
    pipeline.release.set()

    assert_superseded(await finished(ledger, req.runId))


async def test_pipeline_ignoring_cancel_is_detached_and_run_still_ends() -> None:
    pipeline = StubbornPipeline()
    executor, ledger = make_executor(pipeline)
    req = request()
    try:
        await executor.submit(req)
        await asyncio.wait_for(pipeline.started.wait(), 1)

        executor.cancel(UUID(req.runId))

        assert_superseded(await finished(ledger, req.runId))
        # 응답하지 않는 작업은 run에서 떼어 내되 실행 슬롯은 끝날 때까지 차지한다.
        await eventually(lambda: executor.active_runs == 0)
        assert executor.stray_tasks == 1
    finally:
        # 회귀로 실행이 끝나지 않아도 테스트가 멈추지 않도록 항상 풀어 준다.
        pipeline.stop.set()
        await asyncio.sleep(0.01)
    await eventually(lambda: executor.stray_tasks == 0)
    # 늦게 끝난 작업의 성공 결과는 저장되지 않는다.
    assert_superseded(await finished(ledger, req.runId))


async def test_cancel_after_finish_unknown_and_repeated_are_harmless() -> None:
    class Quick:
        async def run(self, req, context):
            return succeeded(req)

    executor, ledger = make_executor(Quick())
    req = request()
    await executor.submit(req)
    assert (await finished(ledger, req.runId))["status"] == "SUCCEEDED"
    await eventually(lambda: executor.active_runs == 0)

    assert executor.cancel(UUID(req.runId)) is False
    assert executor.cancel(uuid4()) is False
    assert (await finished(ledger, req.runId))["status"] == "SUCCEEDED"


async def test_repeated_cancel_ends_the_run_once() -> None:
    pipeline = CancellablePipeline()
    executor, ledger = make_executor(pipeline)
    req = request()
    await executor.submit(req)
    await asyncio.wait_for(pipeline.started.wait(), 1)

    for _ in range(3):
        executor.cancel(UUID(req.runId))

    assert_superseded(await finished(ledger, req.runId))
    assert ledger.finish_calls == 1


async def test_cancel_publishes_the_stored_superseded_result_event() -> None:
    hub = RunEventHub()
    pipeline = CancellablePipeline()
    executor, ledger = make_executor(pipeline)
    executor.events = hub
    req = request()
    with hub.subscribe(req.runId) as queue:
        await executor.submit(req)
        await asyncio.wait_for(pipeline.started.wait(), 1)

        executor.cancel(UUID(req.runId))
        await finished(ledger, req.runId)
        await eventually(lambda: not queue.empty())

        events = []
        while not queue.empty():
            events.append(queue.get_nowait())
    results = [event for event in events if event is not None and event.eventType == "result"]
    assert [event.payload.status for event in results] == ["SUPERSEDED"]


async def test_cancelling_one_run_does_not_touch_another() -> None:
    class Router:
        def __init__(self) -> None:
            self.pipelines = {}

        async def run(self, req, context):
            pipeline = self.pipelines.setdefault(req.runId, CancellablePipeline())
            return await pipeline.run(req, context)

    router = Router()
    executor, ledger = make_executor(router)
    a, b = request(), request()
    await executor.submit(a)
    await executor.submit(b)
    await eventually(lambda: len(router.pipelines) == 2)
    await asyncio.wait_for(router.pipelines[a.runId].started.wait(), 1)

    executor.cancel(UUID(a.runId))

    assert_superseded(await finished(ledger, a.runId))
    assert (await ledger.get(UUID(b.runId))).status == "RUNNING"
    executor.cancel(UUID(b.runId))
    assert_superseded(await finished(ledger, b.runId))


class HangingLLM:
    """모델 응답이 오지 않는 상황. 취소가 없으면 기한까지 기다린다."""

    def __init__(self) -> None:
        self.called = asyncio.Event()
        self.cancelled = False

    async def generate(self, request):
        self.called.set()
        try:
            await asyncio.Event().wait()
        except asyncio.CancelledError:
            self.cancelled = True
            raise


async def test_supervisor_pipeline_stops_waiting_for_the_model_on_cancel() -> None:
    from hops_ai.services.runs.supervisor import SupervisorPipeline

    llm = HangingLLM()
    executor, ledger = make_executor(SupervisorPipeline(llm))
    req = request()
    await executor.submit(req)
    await asyncio.wait_for(llm.called.wait(), 1)

    executor.cancel(UUID(req.runId))

    assert_superseded(await finished(ledger, req.runId))
    await eventually(lambda: executor.occupied_slots == 0)
    # 하네스가 모델 호출 대기를 끊어 파이프라인이 스스로 끝났다(떼어 낸 작업 없음).
    assert llm.cancelled
    assert executor.stray_tasks == 0
