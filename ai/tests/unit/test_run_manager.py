import asyncio
import logging

import pytest

from hops_ai.agents.registry import AgentRegistry, build_fake_registry
from hops_ai.api import schemas as wire
from hops_ai.contracts.analysis import AnalysisOutcome
from hops_ai.fakes.services import build_fake_services
from hops_ai.harness.runner import RunConflict, RunManager
from hops_ai.harness.trace import RunRecord

FIRST_RUN = "00000000-0000-4000-8000-000000000301"
SECOND_RUN = "00000000-0000-4000-8000-000000000302"
OTHER_INQUIRY = "00000000-0000-4000-8000-000000000999"


class RecordingTracer:
    def __init__(self) -> None:
        self.records: list[RunRecord] = []

    def record_run(self, record: RunRecord) -> None:
        self.records.append(record)


class BrokenTracer:
    def record_run(self, record: RunRecord) -> None:
        raise RuntimeError("기록 저장소 장애")


class Gate:
    """첫 실행은 열릴 때까지 기다리게 하는 분석기. 실행이 중간에 있는 상황을 만든다."""

    def __init__(self, blocked_runs: set[str]) -> None:
        self.blocked_runs = blocked_runs
        self.release = asyncio.Event()
        self.started: list[str] = []

    async def analyze(self, data, services):
        self.started.append(data.run_id)
        if data.run_id in self.blocked_runs:
            await self.release.wait()
        return AnalysisOutcome(answer="완료", problems=[], ticket_proposals=[], evidence=[])


def registry_with(analyzer) -> AgentRegistry:
    return AgentRegistry(roles=build_fake_registry().roles, baseline=analyzer)


def manager(make_settings, registry=None, tracer=None, **kwargs) -> RunManager:
    settings = kwargs.pop("settings", None) or make_settings()
    return RunManager(
        settings,
        registry or build_fake_registry(),
        build_fake_services,
        tracer=tracer,
        **kwargs,
    )


async def final_event(handle):
    async with asyncio.timeout(2):
        events = [event async for _, event in handle.events.stream()]
    return events[-1]


async def test_a_request_is_accepted_at_once_and_the_result_follows(
    make_settings, make_wire_request
) -> None:
    runner = manager(make_settings)

    accepted = runner.start(make_wire_request())

    assert accepted.status == "ACCEPTED"
    result = await final_event(runner.get(accepted.runId))
    assert isinstance(result, wire.AiSuccess)
    assert result.problems[0].action == "ASK_USER"


async def test_the_same_run_id_is_executed_only_once(make_settings, make_wire_request) -> None:
    gate = Gate({FIRST_RUN})
    runner = manager(make_settings, registry_with(gate))
    request = make_wire_request(runId=FIRST_RUN)

    first = runner.start(request)
    second = runner.start(request)  # 중복 수신
    await asyncio.sleep(0)
    gate.release.set()
    await final_event(runner.get(FIRST_RUN))

    assert first == second
    assert gate.started == [FIRST_RUN]


async def test_the_same_run_id_for_another_inquiry_version_conflicts(
    make_settings, make_wire_request
) -> None:
    runner = manager(make_settings)
    runner.start(make_wire_request(runId=FIRST_RUN, inquiryVersion=1))

    with pytest.raises(RunConflict):
        runner.start(make_wire_request(runId=FIRST_RUN, inquiryVersion=2))


async def test_a_newer_inquiry_version_supersedes_the_older_unfinished_run(
    make_settings, make_wire_request
) -> None:
    gate = Gate({FIRST_RUN})
    runner = manager(make_settings, registry_with(gate))
    runner.start(make_wire_request(runId=FIRST_RUN, inquiryVersion=1))
    await asyncio.sleep(0.01)  # 첫 실행이 분석 중이다

    runner.start(make_wire_request(runId=SECOND_RUN, inquiryVersion=2))

    old = await final_event(runner.get(FIRST_RUN))
    new = await final_event(runner.get(SECOND_RUN))
    assert isinstance(old, wire.AiFailure)
    assert (old.status, old.error.code) == ("CANCELLED", "INQUIRY_VERSION_CONFLICT")
    assert isinstance(new, wire.AiSuccess)


async def test_an_older_request_arriving_late_does_not_cancel_the_newer_run(
    make_settings, make_wire_request
) -> None:
    gate = Gate({SECOND_RUN})
    runner = manager(make_settings, registry_with(gate))
    runner.start(make_wire_request(runId=SECOND_RUN, inquiryVersion=2))
    await asyncio.sleep(0.01)

    runner.start(make_wire_request(runId=FIRST_RUN, inquiryVersion=1))
    gate.release.set()

    assert isinstance(await final_event(runner.get(SECOND_RUN)), wire.AiSuccess)


async def test_runs_of_another_inquiry_are_left_alone(make_settings, make_wire_request) -> None:
    gate = Gate({FIRST_RUN})
    runner = manager(make_settings, registry_with(gate))
    runner.start(make_wire_request(runId=FIRST_RUN, inquiryVersion=1))
    await asyncio.sleep(0.01)

    runner.start(make_wire_request(runId=SECOND_RUN, inquiryVersion=2, inquiryId=OTHER_INQUIRY))
    gate.release.set()

    assert isinstance(await final_event(runner.get(FIRST_RUN)), wire.AiSuccess)


async def test_shutdown_cancels_running_work_and_still_reports_a_result(
    make_settings, make_wire_request
) -> None:
    gate = Gate({FIRST_RUN})
    runner = manager(make_settings, registry_with(gate))
    runner.start(make_wire_request(runId=FIRST_RUN))
    await asyncio.sleep(0.01)
    assert runner.active_count() == 1

    await runner.shutdown()

    result = await final_event(runner.get(FIRST_RUN))
    assert isinstance(result, wire.AiFailure) and result.status == "CANCELLED"
    assert runner.active_count() == 0


async def test_a_request_that_cannot_be_converted_fails_with_invalid_input(
    make_settings, make_request_json
) -> None:
    value = make_request_json(runId=FIRST_RUN)
    value["evidence"][0]["kind"] = "TOOL"  # 조회 시각 없는 도구 근거
    runner = manager(make_settings)

    runner.start(wire.AiRequest.model_validate(value))

    result = await final_event(runner.get(FIRST_RUN))
    assert isinstance(result, wire.AiFailure)
    assert (result.status, result.error.code) == ("ERROR", "INVALID_INPUT")


async def test_a_deadline_already_passed_gives_timed_out(make_settings, make_wire_request) -> None:
    runner = manager(make_settings)

    runner.start(make_wire_request(runId=FIRST_RUN, deadline_seconds=-5))

    result = await final_event(runner.get(FIRST_RUN))
    assert isinstance(result, wire.AiFailure)
    assert (result.status, result.error.code) == ("TIMED_OUT", "DEADLINE_EXCEEDED")


async def test_a_failure_while_setting_up_still_ends_with_a_result(
    make_settings, make_wire_request
) -> None:
    def broken_factory(runtime, usage):
        raise RuntimeError("secret-token 연결 실패")

    runner = RunManager(make_settings(), build_fake_registry(), broken_factory)

    runner.start(make_wire_request(runId=FIRST_RUN))

    result = await final_event(runner.get(FIRST_RUN))
    assert isinstance(result, wire.AiFailure)
    assert result.error.code == "SERVICE_UNAVAILABLE"
    assert "secret-token" not in result.model_dump_json()


async def test_a_result_that_cannot_be_sent_outside_is_not_reported_as_success(
    make_settings, make_wire_request
) -> None:
    from hops_ai.contracts.agent_1 import IntakeProblem

    unfinished = IntakeProblem(
        ref="new:p1",
        summary="x",
        category=None,
        questionCount=0,
        nextAction="SEARCH",
        question=None,
        evidenceIds=[],
    )

    class Returns:
        async def analyze(self, data, services):
            return AnalysisOutcome(
                answer="", problems=[unfinished], ticket_proposals=[], evidence=[]
            )

    runner = manager(make_settings, registry_with(Returns()))

    runner.start(make_wire_request(runId=FIRST_RUN))

    result = await final_event(runner.get(FIRST_RUN))
    assert isinstance(result, wire.AiFailure)
    assert result.error.code == "SERVICE_UNAVAILABLE"


async def test_multi_agent_progress_events_come_before_the_result(
    make_settings, make_wire_request
) -> None:
    runner = manager(make_settings, settings=make_settings(agent_mode="multi"))

    runner.start(make_wire_request(runId=FIRST_RUN))
    await final_event(runner.get(FIRST_RUN))

    kinds = [type(event).__name__ for _, event in runner.get(FIRST_RUN).events.history()]
    assert kinds == ["AiProgress", "AiProgress", "AiSuccess"]


async def test_the_run_record_has_numbers_and_ids_but_no_user_content(
    make_settings, make_wire_request
) -> None:
    tracer = RecordingTracer()
    runner = manager(make_settings, tracer=tracer)

    runner.start(make_wire_request(runId=FIRST_RUN))
    await final_event(runner.get(FIRST_RUN))

    [record] = tracer.records
    assert (record.run_id, record.status, record.error_code) == (FIRST_RUN, "OK", None)
    assert (record.mode, record.agent_mode) == ("fake", "single")
    assert record.event_count == 1
    assert record.llm_calls_used == 0
    assert "VPN 접속 오류가 나요" not in repr(record)


async def test_a_broken_tracer_does_not_change_the_result(make_settings, make_wire_request) -> None:
    runner = manager(make_settings, tracer=BrokenTracer())

    runner.start(make_wire_request(runId=FIRST_RUN))

    assert isinstance(await final_event(runner.get(FIRST_RUN)), wire.AiSuccess)
    await runner.get(FIRST_RUN).task  # 기록 실패가 실행 태스크를 실패시키지 않는다


async def test_the_run_record_is_logged_without_the_conversation(
    make_settings, make_wire_request, caplog
) -> None:
    from hops_ai.harness.trace import LoggingTracer

    runner = manager(make_settings, tracer=LoggingTracer())

    with caplog.at_level(logging.INFO, logger="hops_ai.run"):
        runner.start(make_wire_request(runId=FIRST_RUN))
        await final_event(runner.get(FIRST_RUN))

    text = " ".join(record.getMessage() for record in caplog.records)
    assert "run_finished" in text and FIRST_RUN in text
    assert "VPN 접속 오류가 나요" not in text


async def test_finished_runs_are_kept_for_a_while_and_then_forgotten(
    make_settings, make_wire_request, clock
) -> None:
    runner = manager(make_settings, clock=clock, retention_seconds=100)
    runner.start(make_wire_request(runId=FIRST_RUN, deadline_seconds=3600))
    await final_event(runner.get(FIRST_RUN))

    clock.advance(50)
    runner.start(
        make_wire_request(runId=SECOND_RUN, inquiryId=OTHER_INQUIRY, deadline_seconds=3600)
    )
    assert runner.get(FIRST_RUN) is not None  # 아직 보관 중이다

    clock.advance(100)
    runner.start(
        make_wire_request(
            runId="00000000-0000-4000-8000-000000000303",
            inquiryId="00000000-0000-4000-8000-000000000998",
            deadline_seconds=3600,
        )
    )

    assert runner.get(FIRST_RUN) is None
