"""진행·종료 순번, 저장 경계, 유실·느린 구독자·연결 종료를 외부 호출 없이 검사한다."""

import asyncio
import json
from uuid import UUID

import pytest

from hops_ai.api.routes.runs import get_run_events
from hops_ai.services.runs.admission import RunAdmissionService
from hops_ai.services.runs.events import RunEventHub, RunEvents
from hops_ai.services.runs.executor import RunExecutor, server_ceilings
from hops_ai.settings import Settings
from tests.doubles.ledger import MemoryLedger
from tests.doubles.run_requests import expires_in, inquiry_request, succeeded


def request():
    return inquiry_request(expires_at=expires_in(60))


def decode(frame):
    return json.loads(next(line[6:] for line in frame.splitlines() if line.startswith("data: ")))


async def test_live_progress_then_stored_result_and_late_progress_is_blocked():
    req = request()
    ledger = MemoryLedger()
    hub = RunEventHub()
    started, release = asyncio.Event(), asyncio.Event()

    class Pipeline:
        async def run(self, request, context):
            started.set()
            await release.wait()
            context.report_progress("INQUIRY")
            return succeeded(request)

    settings = Settings(_env_file=None)
    executor = RunExecutor(
        ledger,
        RunAdmissionService(ledger, server_ceilings(settings)),
        Pipeline(),
        settings,
        events=hub,
    )
    await executor.submit(req)
    await started.wait()
    response = await get_run_events(req.runId, ledger, hub)
    first = asyncio.create_task(anext(response.body_iterator))
    await asyncio.sleep(0)
    release.set()
    progress = decode(await asyncio.wait_for(first, 2))
    result = decode(await anext(response.body_iterator))
    assert progress["eventType"] == "progress"
    assert progress["payload"]["role"] == "INQUIRY"
    assert result["eventType"] == "result"
    assert [progress["seq"], result["seq"]] == [1, 2]
    stored = await ledger.get(UUID(req.runId))
    assert stored.result == result["payload"]
    assert stored.last_seq == result["seq"]
    with pytest.raises(StopAsyncIteration):
        await anext(response.body_iterator)
    assert not hub._subscribers


async def test_finished_run_can_be_read_after_connection_was_missed():
    req = request()
    ledger = MemoryLedger()
    await ledger.accept(UUID(req.runId), req.model_dump(mode="json"))
    await ledger.start(UUID(req.runId))
    await ledger.finish(UUID(req.runId), "SUCCEEDED", succeeded(req).model_dump(mode="json"), 3)
    response = await get_run_events(req.runId, ledger, RunEventHub())
    frames = [frame async for frame in response.body_iterator]
    assert len(frames) == 1
    assert decode(frames[0])["seq"] == 3
    assert decode(frames[0])["eventType"] == "result"


async def test_disconnect_removes_subscription_without_cancelling_run():
    req = request()
    ledger, hub = MemoryLedger(), RunEventHub()
    await ledger.accept(UUID(req.runId), req.model_dump(mode="json"))
    response = await get_run_events(req.runId, ledger, hub)
    waiting = asyncio.create_task(anext(response.body_iterator))
    await asyncio.sleep(0)
    waiting.cancel()
    with pytest.raises(asyncio.CancelledError):
        await waiting
    assert not hub._subscribers
    assert (await ledger.get(UUID(req.runId))).status == "QUEUED"


def test_slow_subscriber_is_bounded_and_other_run_is_isolated():
    req = request()
    hub = RunEventHub()
    events = RunEvents(req.runId, hub)
    with hub.subscribe(req.runId) as slow, hub.subscribe("other") as other:
        for step in range(65):
            events.progress("SUPERVISOR", f"진행 {step}")
        assert slow.qsize() == 1
        assert slow.get_nowait() is None
        assert other.empty()


def test_result_closes_progress_and_reserves_one_final_seq():
    req = request()
    hub = RunEventHub()
    events = RunEvents(req.runId, hub)
    with hub.subscribe(req.runId) as queue:
        events.progress("KNOWLEDGE")
        final = events.prepare_result(succeeded(req))
        events.progress("TICKET")
        assert final.seq == 2
        assert queue.qsize() == 1  # 저장 전에는 최종 이벤트를 publish하지 않는다
        assert (
            queue.get_nowait().payload.displaySummary == "승인된 가이드와 해결 사례를 찾고 있어요"
        )


async def test_failed_final_store_does_not_publish_success():
    req = request()
    ledger = MemoryLedger(finish_failures=10)
    hub = RunEventHub()

    class Pipeline:
        async def run(self, request, context):
            context.report_progress("SUPERVISOR")
            return succeeded(request)

    settings = Settings(_env_file=None)
    executor = RunExecutor(
        ledger,
        RunAdmissionService(ledger, server_ceilings(settings)),
        Pipeline(),
        settings,
        events=hub,
        store_retry_delays=(0,),
    )
    with hub.subscribe(req.runId) as queue:
        await executor.submit(req)
        for _ in range(100):
            if (await ledger.get(UUID(req.runId))).status == "UNKNOWN":
                break
            await asyncio.sleep(0.01)
        assert (await ledger.get(UUID(req.runId))).status == "UNKNOWN"
        assert queue.qsize() == 1
        assert queue.get_nowait().eventType == "progress"
