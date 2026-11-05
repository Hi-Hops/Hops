"""Knowledge를 지금 호출해도 성립하는지(선택지 제외 규칙).

문제가 아직 없는 첫 문의에서 실제 모델이 Inquiry를 건너뛰고 Knowledge를 골라 실행이 실패한
사례를 재현한다. 이 규칙은 호출이 성립하지 않는 경우만 선택지에서 빼는 사실 판단이며,
성립하는 역할 사이의 선택(예: 검색 결과 없음 뒤의 질문·티켓)은 제한하지 않는다.
모델은 Fake이므로 실제 모델의 판단 품질은 검증하지 않는다.
"""

import json

import pytest

from hops_ai.agents.knowledge import SearchKnowledgeAgent
from hops_ai.agents.knowledge.agent import guidable_problem
from hops_ai.agents.registry import build_registry
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.execution import RunKind
from hops_ai.contracts.wire import GuideProposal
from hops_ai.services.retrieval.fixture_source import FixtureKnowledgeSource
from hops_ai.services.runs.supervisor import implemented_roles, selectable_roles
from hops_ai.supervisor.graph import Supervisor
from hops_ai.supervisor.policy import get_allowed_roles
from tests.doubles.llm import FakeLLMClient
from tests.supervisor_fixtures import make_evidence, make_request, running_state
from tests.unit.test_knowledge_empty_handoff import (
    empty_search,
    how_to_problem,
    services_for,
    state_after,
)
from tests.unit.test_knowledge_wiring import decision, how_to_first_message


def registry():
    return build_registry(FixtureKnowledgeSource())


def roles_for(state, agents=None) -> set[AgentRole]:
    """Supervisor가 실제로 보는 선택지: 선택 가능한 역할과 실행 종류별 허용 역할의 교집합."""
    agents = registry() if agents is None else agents
    selectable = selectable_roles(agents, implemented_roles(agents), state)
    return selectable & get_allowed_roles(RunKind(state["input"].kind))


def test_no_problem_yet_means_nothing_to_search() -> None:
    state = running_state(how_to_first_message())

    assert guidable_problem(state) is None
    assert roles_for(state) == {AgentRole.INQUIRY}


def test_guidable_how_to_problem_makes_knowledge_selectable() -> None:
    state = state_after(None, how_to_problem())

    assert guidable_problem(state) is not None
    assert roles_for(state) == {AgentRole.INQUIRY, AgentRole.KNOWLEDGE}


@pytest.mark.parametrize(
    "changes",
    [
        {"processingType": "INVESTIGATION"},
        {"reportedSystemCandidateId": None},
    ],
)
def test_problem_that_cannot_be_guided_hides_knowledge(changes) -> None:
    state = state_after(None, how_to_problem(**changes))

    assert guidable_problem(state) is None
    assert AgentRole.KNOWLEDGE not in roles_for(state)
    assert AgentRole.INQUIRY in roles_for(state)
    assert (AgentRole.TICKET in roles_for(state)) == (
        changes.get("processingType") == "INVESTIGATION"
    )


def test_problem_already_searched_is_not_searched_again_but_other_options_stay() -> None:
    # 같은 문제를 같은 질의로 다시 검색해도 같은 결과다. 검색 뒤의 질문·티켓은 막지 않는다.
    state = state_after(empty_search(), how_to_problem())

    assert guidable_problem(state) is None
    assert roles_for(state) == {AgentRole.INQUIRY, AgentRole.TICKET}


def test_knowledge_stays_selectable_for_a_problem_that_was_not_searched_yet() -> None:
    state = state_after(empty_search(), how_to_problem("new:1"), how_to_problem("new:2"))

    target = guidable_problem(state)

    assert target is not None and target.problemRef == "new:2"
    assert AgentRole.KNOWLEDGE in roles_for(state)


def test_knowledge_is_hidden_once_a_guide_exists() -> None:
    state = state_after(None, how_to_problem())
    guide = GuideProposal(messageText="안내", evidenceIds=[make_evidence().id], problemRef="new:1")
    state = {**state, "guide": guide}

    assert AgentRole.KNOWLEDGE not in roles_for(state)


def test_roles_without_an_availability_check_are_never_removed_or_added() -> None:
    class TicketWithoutCheck:
        """available_for를 제공하지 않는 명시적 대역."""

        role = AgentRole.TICKET

        async def run(self, request, services):
            raise AssertionError("선택지 계산에서는 호출하지 않는다")

    agents = dict(build_registry())  # Knowledge 연결 전
    agents[AgentRole.TICKET] = TicketWithoutCheck()
    state = running_state(how_to_first_message())

    assert roles_for(state, agents) == {AgentRole.INQUIRY, AgentRole.TICKET}


def test_case_draft_runs_are_not_served_by_this_knowledge_agent() -> None:
    state = running_state(make_request(kind=RunKind.CASE_DRAFT))

    assert SearchKnowledgeAgent(FixtureKnowledgeSource()).available_for(state) is False


async def test_first_decision_never_offers_knowledge_and_a_wrong_pick_is_rejected() -> None:
    request = how_to_first_message()
    llm = FakeLLMClient(decision("CALL_ROLE", AgentRole.KNOWLEDGE))
    agents = registry()
    callable_roles = implemented_roles(agents)
    supervisor = Supervisor(
        agents, available_roles=lambda state: selectable_roles(agents, callable_roles, state)
    )

    result = await supervisor.analyze(request, services_for(request, llm))

    context = json.loads(llm.requests[0].messages[-1].content)
    assert context["allowedRoles"] == ["INQUIRY"]
    # 검색 단계에 들어가기 전에 선택 자체가 거부되어 Knowledge가 호출되지 않는다.
    assert result.status == "FAILED"
    assert len(llm.requests) == 1


async def test_prompt_tells_the_model_that_problems_come_from_inquiry() -> None:
    request = how_to_first_message()
    llm = FakeLLMClient(decision("FINISH"))

    await Supervisor(registry()).analyze(request, services_for(request, llm))

    sent = llm.requests[0]
    assert "Inquiry가 문의를 분석해야 생긴다" in sent.messages[0].content
    assert sent.prompt_version == "supervisor.decision.v6"
