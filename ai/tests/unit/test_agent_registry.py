import pytest

from hops_ai.agents.base import Analyzer, run_analysis
from hops_ai.agents.registry import build_fake_registry, build_registry, select_analyzer
from hops_ai.contracts.agent_1 import Problem
from hops_ai.contracts.analysis import AnalysisOutcome
from hops_ai.fakes.agents import FakeBaselineAgent
from hops_ai.harness.events import RunEvents
from hops_ai.settings import Settings
from hops_ai.supervisor.graph import Supervisor


def test_fake_registry_has_all_four_roles_and_a_baseline() -> None:
    registry = build_fake_registry()

    assert set(registry.roles) == {"INTAKE", "KNOWLEDGE", "DIAGNOSIS", "TICKET"}
    assert isinstance(registry.baseline, FakeBaselineAgent)


def test_fake_mode_builds_the_fake_registry(make_settings) -> None:
    registry = build_registry(make_settings(mode="fake"))

    assert isinstance(registry.baseline, FakeBaselineAgent)


def test_real_mode_is_refused_instead_of_silently_using_fakes(make_settings) -> None:
    with pytest.raises(NotImplementedError):
        build_registry(make_settings(mode="real"))


def test_single_agent_mode_selects_the_baseline(make_settings) -> None:
    registry = build_fake_registry()

    analyzer = select_analyzer(make_settings(agent_mode="single"), registry)

    assert analyzer is registry.baseline


def test_multi_agent_mode_selects_a_supervisor(make_settings) -> None:
    analyzer = select_analyzer(make_settings(agent_mode="multi"), build_fake_registry())

    assert isinstance(analyzer, Supervisor)


def test_agent_mode_comes_from_the_environment_variable(monkeypatch) -> None:
    registry = build_fake_registry()

    monkeypatch.setenv("HOPS_AI_AGENT_MODE", "multi")
    assert isinstance(select_analyzer(Settings(_env_file=None), registry), Supervisor)

    monkeypatch.setenv("HOPS_AI_AGENT_MODE", "single")
    assert select_analyzer(Settings(_env_file=None), registry) is registry.baseline


def test_events_are_handed_to_the_supervisor_per_run(make_settings) -> None:
    events = RunEvents(
        run_id="00000000-0000-4000-8000-000000000001",
        inquiry_id="00000000-0000-4000-8000-000000000003",
        inquiry_version=1,
    )

    analyzer = select_analyzer(
        make_settings(agent_mode="multi"), build_fake_registry(), events=events
    )

    assert analyzer._events is events


@pytest.mark.parametrize("agent_mode", ["single", "multi"])
def test_both_modes_give_an_analyzer(agent_mode, make_settings) -> None:
    analyzer = select_analyzer(make_settings(agent_mode=agent_mode), build_fake_registry())

    assert isinstance(analyzer, Analyzer)


async def analyze_in_mode(agent_mode, data, make_settings, make_services):
    settings = make_settings(agent_mode=agent_mode)
    analyzer = select_analyzer(settings, build_fake_registry())
    return await run_analysis(analyzer, data, make_services(agent_mode=agent_mode))


async def test_single_and_multi_agent_share_one_input_and_result_boundary(
    make_settings, make_services, make_input
) -> None:
    # 같은 입력을 두 구성에 넣어 같은 형태의 결과를 받는다. 비교 평가의 전제다.
    data = make_input()

    single = await analyze_in_mode("single", data, make_settings, make_services)
    multi = await analyze_in_mode("multi", data, make_settings, make_services)

    assert isinstance(single, AnalysisOutcome)
    assert isinstance(multi, AnalysisOutcome)
    assert single == multi


async def test_follow_up_turn_also_matches_between_modes(
    make_settings, make_services, make_input, make_message
) -> None:
    data = make_input(
        messages=[make_message("오류 코드는 809예요.", 603)],
        existing_problems=[
            Problem(
                ref="00000000-0000-4000-8000-000000000201",
                summary="VPN 접속 오류",
                category="NETWORK_ACCESS",
                questionCount=1,
            )
        ],
    )

    single = await analyze_in_mode("single", data, make_settings, make_services)
    multi = await analyze_in_mode("multi", data, make_settings, make_services)

    assert isinstance(single, AnalysisOutcome)
    assert [item.departmentCode for item in single.ticket_proposals] == ["NETWORK"]
    assert single == multi
