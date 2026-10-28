import asyncio

import pytest

from hops_ai.api.schemas import (
    SCHEMA_VERSION,
    AiDelta,
    AiFailure,
    AiProgress,
    AiSuccess,
    ApiErrorBody,
)
from hops_ai.harness.events import RunEvents

RUN_ID = "00000000-0000-4000-8000-000000000300"
INQUIRY_ID = "00000000-0000-4000-8000-000000000200"
MESSAGE_A = "00000000-0000-4000-8000-000000000605"
MESSAGE_B = "00000000-0000-4000-8000-000000000606"


def new_events() -> RunEvents:
    return RunEvents(run_id=RUN_ID, inquiry_id=INQUIRY_ID, inquiry_version=2)


def success(run_id: str = RUN_ID) -> AiSuccess:
    return AiSuccess(
        schemaVersion=SCHEMA_VERSION,
        runId=run_id,
        inquiryId=INQUIRY_ID,
        inquiryVersion=2,
        status="OK",
        answer="담당 부서에 연결할 내용을 정리했어요.",
        problems=[],
        ticketProposals=[],
        error=None,
    )


def timeout_failure() -> AiFailure:
    return AiFailure(
        schemaVersion=SCHEMA_VERSION,
        runId=RUN_ID,
        inquiryId=INQUIRY_ID,
        inquiryVersion=2,
        status="TIMED_OUT",
        error=ApiErrorBody(
            code="DEADLINE_EXCEEDED",
            message="실행 제한 시간을 초과했다",
            requestId=RUN_ID,
            retryable=False,
        ),
    )


async def collect(events: RunEvents, after_id: int = 0) -> list[tuple[int, object]]:
    async with asyncio.timeout(1):
        return [item async for item in events.stream(after_id)]


async def test_events_get_increasing_ids_in_the_order_they_were_sent() -> None:
    events = new_events()

    await events.progress("INTAKE", "문의 내용을 확인해요.", "RUNNING")
    await events.answer_delta(MESSAGE_A, "담당 ")
    await events.finish(success())

    assert [event_id for event_id, _ in events.history()] == [1, 2, 3]
    kinds = [type(event) for _, event in events.history()]
    assert kinds == [AiProgress, AiDelta, AiSuccess]


async def test_progress_steps_are_numbered_from_one() -> None:
    events = new_events()

    await events.progress("INTAKE", "문의 확인", "RUNNING")
    await events.progress("INTAKE", "문의 확인", "DONE")
    await events.progress("TICKET", "티켓 정리", "RUNNING")

    steps = [event.payload.stepNo for _, event in events.history()]
    assert steps == [1, 2, 3]


async def test_answer_delta_sequence_is_counted_per_message() -> None:
    events = new_events()

    await events.answer_delta(MESSAGE_A, "담당 ")
    await events.answer_delta(MESSAGE_B, "다른 ")
    await events.answer_delta(MESSAGE_A, "부서에 ")

    sequences = [(event.payload.messageId, event.payload.seq) for _, event in events.history()]
    assert sequences == [(MESSAGE_A, 1), (MESSAGE_B, 1), (MESSAGE_A, 2)]


async def test_every_event_carries_the_run_identifiers() -> None:
    events = new_events()

    await events.progress("INTAKE", "문의 확인", "RUNNING")

    event = events.history()[0][1]
    assert (event.runId, event.inquiryId, event.inquiryVersion) == (RUN_ID, INQUIRY_ID, 2)
    assert event.schemaVersion == SCHEMA_VERSION


async def test_result_is_sent_only_once() -> None:
    events = new_events()

    assert await events.finish(success()) is True
    assert await events.finish(timeout_failure()) is False

    assert len(events.history()) == 1
    assert isinstance(events.history()[0][1], AiSuccess)
    assert events.finished


async def test_progress_and_delta_after_the_result_are_dropped() -> None:
    events = new_events()
    await events.finish(success())

    assert await events.progress("INTAKE", "늦은 진행", "RUNNING") is False
    assert await events.answer_delta(MESSAGE_A, "늦은 조각") is False

    assert len(events.history()) == 1


async def test_result_for_another_run_is_rejected() -> None:
    events = new_events()

    with pytest.raises(ValueError):
        await events.finish(success(run_id="00000000-0000-4000-8000-000000000999"))

    assert not events.finished


async def test_failure_result_also_ends_the_run() -> None:
    events = new_events()

    await events.finish(timeout_failure())

    assert events.finished
    assert await events.progress("INTAKE", "늦은 진행", "RUNNING") is False


async def test_stream_ends_after_the_result() -> None:
    events = new_events()
    await events.progress("INTAKE", "문의 확인", "RUNNING")
    await events.finish(success())

    received = await collect(events)

    assert [event_id for event_id, _ in received] == [1, 2]


async def test_stream_replays_only_events_after_the_given_id() -> None:
    events = new_events()
    await events.progress("INTAKE", "문의 확인", "RUNNING")
    await events.answer_delta(MESSAGE_A, "담당 ")
    await events.finish(success())

    received = await collect(events, after_id=2)

    assert [event_id for event_id, _ in received] == [3]


async def test_reconnecting_after_the_last_event_yields_nothing_and_ends() -> None:
    events = new_events()
    await events.finish(success())

    assert await collect(events, after_id=1) == []


async def test_stream_waits_for_events_sent_later() -> None:
    events = new_events()
    consumer = asyncio.create_task(collect(events))
    await asyncio.sleep(0)  # 소비자가 먼저 기다리기 시작하게 한다

    await events.progress("INTAKE", "문의 확인", "RUNNING")
    await events.answer_delta(MESSAGE_A, "담당 ")
    await events.finish(success())
    received = await consumer

    assert [event_id for event_id, _ in received] == [1, 2, 3]


async def test_every_consumer_receives_the_same_events() -> None:
    events = new_events()
    first = asyncio.create_task(collect(events))
    second = asyncio.create_task(collect(events))
    await asyncio.sleep(0)

    await events.progress("INTAKE", "문의 확인", "RUNNING")
    await events.finish(success())

    assert await first == await second
