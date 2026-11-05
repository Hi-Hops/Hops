"""실행 기록의 저장·복원과 재판정. Fake 모델만 쓰며 실제 모델을 부르지 않는다."""

import json
from pathlib import Path

import pytest

from evaluation.harness.checks import check_trace
from evaluation.harness.runner import run_scenario
from evaluation.harness.scenario import parse_scenario
from evaluation.harness.trace_io import load_trace, save_trace, trace_from_dict, trace_to_dict
from evaluation.pilot import run_batch
from evaluation.recheck import main as recheck_main
from evaluation.recheck import recheck
from hops_ai.contracts.domain import AgentRole
from tests.doubles.llm import FakeLLMClient
from tests.eval_fixtures import ai_result, fact, problem_dict, request_with, run_trace, turn
from tests.supervisor_fixtures import PROBLEM
from tests.unit.test_eval_pilot import three_scenarios, two_turn_llm
from tests.unit.test_eval_runner import AGENTS, decision
from tests.unit.test_eval_user_sim import SCENARIO


async def real_trace():
    return await run_scenario(parse_scenario(SCENARIO), llm=two_turn_llm(), agents=AGENTS)


async def test_a_trace_survives_a_json_round_trip_unchanged() -> None:
    trace = await real_trace()

    restored = trace_from_dict(json.loads(json.dumps(trace_to_dict(trace), ensure_ascii=False)))

    assert restored == trace
    assert len(restored.turns) == 2
    assert check_trace(restored) == check_trace(trace)


async def test_save_and_load_use_the_scenario_id_as_the_file_name(tmp_path: Path) -> None:
    trace = await real_trace()

    path = save_trace(trace, tmp_path / "nested")

    assert path.name == f"{trace.scenario_id}.trace.json"
    assert load_trace(path) == trace


def test_unknown_trace_format_is_refused() -> None:
    with pytest.raises(ValueError, match="형식"):
        trace_from_dict({"format": 99})


async def test_batch_saves_each_trace_and_recheck_reads_them_back(tmp_path: Path) -> None:
    decisions = [
        decision("CALL_ROLE", AgentRole.INQUIRY),
        decision("ASK_USER"),
        decision("CALL_ROLE", AgentRole.INQUIRY),
        decision("CALL_ROLE", AgentRole.KNOWLEDGE),
        decision("PROVIDE_GUIDE"),
    ]

    await run_batch(
        three_scenarios()[:2],
        llm=FakeLLMClient(*decisions, *decisions),
        agents_for=lambda _: AGENTS,
        max_calls=30,
        on_trace=lambda trace: save_trace(trace, tmp_path),
    )

    assert sorted(path.name for path in tmp_path.glob("*.trace.json")) == [
        "S-batch-0-001.trace.json",
        "S-batch-1-001.trace.json",
    ]
    assert recheck([tmp_path]) == {"S-batch-0-001": [], "S-batch-1-001": []}


def test_recheck_applies_the_current_checkers_to_a_saved_trace(tmp_path: Path) -> None:
    request = request_with((("m1", "전자결재에서 문서가 안 보여요"),))
    empty_quote = ai_result(
        request, problems=[problem_dict(PROBLEM, [fact("하려던 업무·화면: ", "m1")])]
    )
    save_trace(run_trace(turn(request, empty_quote)), tmp_path)

    results = recheck([tmp_path])

    assert list(results.values()) == [["P6 턴 1: 사실의 인용이 비어 있다"]]
    assert recheck_main([str(tmp_path)]) == 1
    assert recheck_main([]) == 2
