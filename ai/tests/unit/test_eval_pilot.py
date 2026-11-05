"""시범 실행 도구의 토큰 기록과 전체 호출 상한. Fake 모델만 쓰며 실제 모델을 부르지 않는다."""

import asyncio
from datetime import UTC, datetime

import pytest

from evaluation.harness.budget import CallCap, CallCapExceeded
from evaluation.harness.scenario import parse_scenario
from evaluation.pilot import (
    FaultyKnowledgeSource,
    exit_code,
    finish_run_info,
    main,
    run_batch,
    run_metadata,
    run_pilot,
    select_scenarios,
)
from hops_ai.contracts.domain import AgentRole
from hops_ai.harness.errors import RetrievalUnavailable
from hops_ai.services.llm.client import LLMUsage
from tests.doubles.llm import FakeLLMClient
from tests.unit.test_eval_runner import AGENTS, decision
from tests.unit.test_eval_user_sim import SCENARIO


def two_turn_llm(**kwargs) -> FakeLLMClient:
    return FakeLLMClient(
        decision("CALL_ROLE", AgentRole.INQUIRY),
        decision("ASK_USER"),
        decision("CALL_ROLE", AgentRole.INQUIRY),
        decision("CALL_ROLE", AgentRole.KNOWLEDGE),
        decision("PROVIDE_GUIDE"),
        **kwargs,
    )


async def test_report_sums_calls_and_reported_tokens() -> None:
    llm = two_turn_llm(usage=LLMUsage(input_tokens=10, output_tokens=5))

    report = await run_pilot(parse_scenario(SCENARIO), llm=llm, agents=AGENTS, max_calls=30)

    assert report["totals"]["modelCalls"] == 5
    assert report["totals"]["inputTokens"] == 50
    assert report["totals"]["outputTokens"] == 25
    assert report["callCap"] == {"max": 30, "used": 5, "exceeded": False}
    assert [turn["modelCalls"] for turn in report["turns"]] == [2, 3]
    assert report["turns"][1]["guide"] == "문서함에서 기간을 늘려 조회해 보세요."
    assert report["safetyViolations"] == []


async def test_total_call_cap_stops_real_calls_before_exceeding_it() -> None:
    llm = two_turn_llm()

    report = await run_pilot(parse_scenario(SCENARIO), llm=llm, agents=AGENTS, max_calls=3)

    assert len(llm.requests) == 3  # 상한을 넘는 원격 호출은 일어나지 않는다
    assert report["callCap"]["exceeded"] is True
    assert report["callCap"]["used"] == 3
    assert report["turns"][-1]["status"] != "SUCCEEDED"


def test_call_cap_refuses_without_consuming_past_the_limit() -> None:
    cap = CallCap(2)
    cap.reserve()
    cap.reserve()

    with pytest.raises(CallCapExceeded):
        cap.reserve()

    assert (cap.used, cap.exceeded) == (2, True)


def test_call_cap_needs_a_positive_limit() -> None:
    with pytest.raises(ValueError):
        CallCap(0)


def test_pilot_refuses_to_start_without_an_api_key(monkeypatch) -> None:
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)

    with pytest.raises(SystemExit, match="OPENAI_API_KEY"):
        main(["ea-search-period", "--max-calls", "5"])


async def test_report_lists_what_the_ai_secured_for_each_problem() -> None:
    report = await run_pilot(
        parse_scenario(SCENARIO), llm=two_turn_llm(), agents=AGENTS, max_calls=30
    )

    problem = report["turns"][-1]["problems"][0]
    assert problem["intent"] == "HOW_TO"
    assert problem["reportedSystem"] == "ELECTRONIC_APPROVAL"
    assert problem["facts"]
    assert problem["unknowns"] == []


def three_scenarios() -> list:
    return [
        parse_scenario({**SCENARIO, "scenarioId": f"S-batch-{n}-001", "caseFamily": f"batch-{n}"})
        for n in range(3)
    ]


async def test_batch_never_exceeds_the_total_cap_and_marks_skipped_scenarios() -> None:
    decisions = [
        decision("CALL_ROLE", AgentRole.INQUIRY),
        decision("ASK_USER"),
        decision("CALL_ROLE", AgentRole.INQUIRY),
        decision("CALL_ROLE", AgentRole.KNOWLEDGE),
        decision("PROVIDE_GUIDE"),
    ]
    llm = FakeLLMClient(*decisions, *decisions, *decisions)

    report = await run_batch(
        three_scenarios(), llm=llm, agents_for=lambda _: AGENTS, max_calls=7, per_scenario_calls=30
    )

    assert len(llm.requests) == 7
    assert report["totalCallCap"] == {"max": 7, "used": 7, "exceeded": True}
    first, second, third = report["scenarios"]
    assert first["callCap"]["used"] == 5 and not first["callCap"]["exceeded"]
    assert second["callCap"]["exceeded"] is True  # 남은 2회에서 멈춤
    assert third == {"scenarioId": "S-batch-2-001", "skipped": "TOTAL_CAP_REACHED"}
    assert report["totals"]["scenarios"] == 2 and report["totals"]["skipped"] == 1


async def test_batch_applies_the_per_scenario_cap() -> None:
    llm = FakeLLMClient(decision("CALL_ROLE", AgentRole.INQUIRY), repeat_last=True)

    report = await run_batch(
        three_scenarios()[:1],
        llm=llm,
        agents_for=lambda _: AGENTS,
        max_calls=100,
        per_scenario_calls=2,
    )

    assert len(llm.requests) == 2
    assert report["totals"]["scenariosHittingTheirCap"] == ["S-batch-0-001"]


def test_select_scenarios_picks_the_dev_split_and_refuses_test_without_a_flag() -> None:
    dev = select_scenarios([], "DEV", allow_test=False)

    assert len(dev) == 12 and {item.split for item in dev} == {"DEV"}
    with pytest.raises(SystemExit, match="TEST"):
        select_scenarios([], "TEST", allow_test=False)
    assert len(select_scenarios([], "TEST", allow_test=True)) == 8
    with pytest.raises(SystemExit):
        select_scenarios(["ea-search-period"], "DEV", allow_test=False)
    with pytest.raises(SystemExit):
        select_scenarios([], None, allow_test=False)


def test_single_test_family_is_also_refused_without_the_flag() -> None:
    with pytest.raises(SystemExit, match="TEST"):
        select_scenarios(["file-sharelink-howto"], None, allow_test=False)


async def test_injected_fault_raises_retrieval_unavailable() -> None:
    with pytest.raises(RetrievalUnavailable):
        await FaultyKnowledgeSource().search(None)  # type: ignore[arg-type]


class SlowLLM:
    """응답이 오래 걸리는 모델. 시나리오 시간 제한이 진행 중인 호출을 끊는지 본다."""

    async def generate(self, request):
        await asyncio.sleep(5)
        raise AssertionError("시간 제한 안에 도달하면 안 된다")


async def test_scenario_timeout_keeps_finished_turns_and_marks_the_scenario() -> None:
    report = await run_pilot(
        parse_scenario(SCENARIO),
        llm=SlowLLM(),
        agents=AGENTS,
        max_calls=30,
        timeout_seconds=0.05,
    )

    assert report["timedOut"] == 0.05
    assert report["turns"] == []
    assert report["callCap"]["used"] == 1  # 진행 중이던 호출도 상한에는 계산돼 있다
    assert report["totals"]["modelCalls"] == 0
    assert report["totals"]["modelCallsInFlight"] == report["callCap"]["used"]


async def test_batch_reports_progress_through_callbacks_and_continues_after_a_timeout() -> None:
    started: list[str] = []
    finished: list[str] = []
    turns: list[tuple[str, int]] = []

    report = await run_batch(
        three_scenarios()[:2],
        llm=SlowLLM(),
        agents_for=lambda _: AGENTS,
        max_calls=10,
        scenario_timeout=0.05,
        on_start=lambda n, total, sc: started.append(f"{n}/{total}"),
        on_turn=lambda sid, i, t: turns.append((sid, i)),
        on_report=lambda r: finished.append(r["scenarioId"]),
    )

    assert started == ["1/2", "2/2"]
    assert finished == ["S-batch-0-001", "S-batch-1-001"]
    assert report["totals"]["scenariosTimedOut"] == ["S-batch-0-001", "S-batch-1-001"]
    assert report["totalCallCap"]["used"] == 2


async def test_batch_calls_on_turn_with_scenario_id_and_turn_number() -> None:
    turns: list[tuple[str, int]] = []

    await run_batch(
        three_scenarios()[:1],
        llm=two_turn_llm(),
        agents_for=lambda _: AGENTS,
        max_calls=30,
        on_turn=lambda sid, i, t: turns.append((sid, i)),
    )

    assert turns == [("S-batch-0-001", 1), ("S-batch-0-001", 2)]


def test_several_families_are_selected_in_the_given_order() -> None:
    picked = select_scenarios(
        ["system-correction", "ea-notify-improvement"], None, allow_test=False
    )

    assert [item.case_family for item in picked] == ["system-correction", "ea-notify-improvement"]


def test_run_metadata_records_the_conditions_needed_to_compare_runs() -> None:
    meta = run_metadata("some-model")

    assert meta["model"] == "some-model"
    assert meta["startedAt"].endswith("+00:00")
    assert meta["runLimits"]["maxRetries"] == 2  # Spring의 설정값과 같다
    assert "FIXTURE" in meta["knowledgeSource"]
    assert set(meta) >= {"gitCommit", "gitDirty"}


async def test_report_records_the_prompt_versions_used() -> None:
    report = await run_pilot(
        parse_scenario(SCENARIO), llm=two_turn_llm(), agents=AGENTS, max_calls=30
    )

    assert report["promptVersions"] and all(isinstance(v, str) for v in report["promptVersions"])


async def test_calls_rejected_by_the_cap_are_not_counted_as_model_calls() -> None:
    llm = two_turn_llm()

    report = await run_pilot(parse_scenario(SCENARIO), llm=llm, agents=AGENTS, max_calls=3)

    assert report["callCap"]["used"] == 3
    assert report["totals"]["modelCalls"] == 3 == len(llm.requests)


def test_final_run_info_keeps_the_start_time_and_adds_the_finish_time() -> None:
    started = {"startedAt": "2026-11-05T03:00:00+00:00", "model": "m"}
    later = datetime(2026, 11, 5, 3, 5, tzinfo=UTC)

    final = finish_run_info(started, later)

    assert final["startedAt"] == "2026-11-05T03:00:00+00:00"
    assert final["finishedAt"] == "2026-11-05T03:05:00+00:00"
    assert started == {"startedAt": "2026-11-05T03:00:00+00:00", "model": "m"}  # 원본은 그대로


async def test_exit_code_is_nonzero_when_a_scenario_timed_out() -> None:
    clean = await run_batch(
        three_scenarios()[:1], llm=two_turn_llm(), agents_for=lambda _: AGENTS, max_calls=30
    )
    timed_out = await run_batch(
        three_scenarios()[:1],
        llm=SlowLLM(),
        agents_for=lambda _: AGENTS,
        max_calls=30,
        scenario_timeout=0.05,
    )

    assert exit_code(clean) == 0
    assert timed_out["totals"]["scenariosTimedOut"] and not timed_out["totalCallCap"]["exceeded"]
    assert exit_code(timed_out) == 1  # 시간 초과만 있어도 성공으로 끝나지 않는다
