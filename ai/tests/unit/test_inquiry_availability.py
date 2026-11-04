"""할 일이 없는 Inquiry 재호출을 선택지에서 빼는 규칙.

실제 모델 시험에서 Knowledge가 근거를 못 찾은 뒤 담당자 전달 정보가 이미 갖춰졌는데도
Supervisor가 Inquiry를 반복 호출해 모델 호출 한도를 넘겼다. 그 상태의 Inquiry는 같은 원문에서
같은 사실을 다시 뽑을 뿐이다. 할 일이 없음이 증명되는 경우만 빼며, 호출이 성립하는 역할 사이의
선택은 제한하지 않는다. 모델은 Fake이므로 실제 모델의 판단은 검증하지 않는다.
"""

import json

import pytest

from hops_ai.agents.inquiry.extraction import QuotedValue
from hops_ai.agents.registry import build_runtime_registry
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.execution import RunKind
from hops_ai.services.retrieval.fixture_source import FixtureKnowledgeSource
from hops_ai.services.runs.supervisor import implemented_roles, selectable_roles
from hops_ai.supervisor.graph import Supervisor
from hops_ai.supervisor.policy import get_allowed_roles
from tests.doubles.llm import FakeLLMClient
from tests.supervisor_fixtures import MESSAGE, make_details, make_problem
from tests.unit.test_handoff_readiness import how_to
from tests.unit.test_knowledge_empty_handoff import (
    empty_search,
    error_search,
    how_to_extraction,
    no_guide_request,
    services_for,
    state_after,
)
from tests.unit.test_knowledge_wiring import decision

COMPLETE = {"taskOrScreen": "결재선 변경", "expectedBehavior": "한꺼번에 바꾸고 싶어요"}


def roles_for(state) -> set[AgentRole]:
    agents = build_runtime_registry(FixtureKnowledgeSource())
    selectable = selectable_roles(agents, implemented_roles(agents), state)
    return selectable & get_allowed_roles(RunKind(state["input"].kind))


def test_inquiry_is_hidden_when_nothing_is_missing_after_an_empty_search() -> None:
    state = state_after(empty_search(), how_to(**COMPLETE))

    assert roles_for(state) == {AgentRole.KNOWLEDGE, AgentRole.TICKET}


def test_inquiry_stays_while_something_is_still_missing() -> None:
    state = state_after(empty_search(), how_to(taskOrScreen="결재선 변경"))

    assert AgentRole.INQUIRY in roles_for(state)


def test_inquiry_stays_before_the_search_even_with_complete_details() -> None:
    state = state_after(None, how_to(**COMPLETE))

    assert AgentRole.INQUIRY in roles_for(state)


def test_inquiry_stays_after_a_search_error() -> None:
    state = state_after(error_search(), how_to(**COMPLETE))

    assert AgentRole.INQUIRY in roles_for(state)


def test_inquiry_stays_when_another_kind_of_problem_is_still_unfinished() -> None:
    symptom = make_problem("new:2").model_copy(update={"details": make_details()})
    state = state_after(empty_search(), how_to(**COMPLETE), symptom)

    assert AgentRole.INQUIRY in roles_for(state)


def test_item_the_user_said_they_do_not_know_does_not_keep_inquiry_available() -> None:
    problem = how_to(taskOrScreen="결재선 변경").model_copy(update={"unknowns": ["기대한 동작"]})
    state = state_after(empty_search(), problem)

    assert AgentRole.INQUIRY not in roles_for(state)


def test_problem_without_a_system_keeps_inquiry_available() -> None:
    problem = how_to(**COMPLETE).model_copy(update={"reportedSystemCandidateId": None})
    state = state_after(empty_search(), problem)

    assert AgentRole.INQUIRY in roles_for(state)


def test_state_without_any_problem_keeps_inquiry_available() -> None:
    state = state_after(empty_search())

    assert AgentRole.INQUIRY in roles_for(state)


@pytest.mark.parametrize("processing_type", ["INVESTIGATION", "REVIEW_REQUIRED"])
def test_only_how_to_problems_count_as_ready(processing_type) -> None:
    problem = how_to(**COMPLETE).model_copy(update={"processingType": processing_type})
    state = state_after(empty_search(), problem)

    assert AgentRole.INQUIRY in roles_for(state)


async def test_supervisor_after_an_empty_search_no_longer_offers_inquiry_when_ready() -> None:
    request = no_guide_request()
    quote = QuotedValue(messageId=MESSAGE, quote="도장 이미지 등록")
    llm = FakeLLMClient(
        decision("CALL_ROLE", AgentRole.INQUIRY),
        how_to_extraction(desired=quote),
        decision("CALL_ROLE", AgentRole.KNOWLEDGE),
        decision("CALL_ROLE", AgentRole.TICKET),
        decision("PROPOSE_TICKETS"),
    )
    agents = build_runtime_registry(FixtureKnowledgeSource())
    callable_roles = implemented_roles(agents)
    supervisor = Supervisor(
        agents, available_roles=lambda state: selectable_roles(agents, callable_roles, state)
    )

    result = await supervisor.analyze(request, services_for(request, llm))

    assert result.status == "SUCCEEDED", result.error
    after_empty = json.loads(llm.requests[3].messages[-1].content)
    assert after_empty["allowedRoles"] == ["KNOWLEDGE", "TICKET"]
    assert after_empty["handoffReadiness"][0]["missingForHandoff"] == []
    # 검색 전과 첫 분석 전에는 Inquiry가 선택지에 있다.
    assert "INQUIRY" in json.loads(llm.requests[2].messages[-1].content)["allowedRoles"]
    assert "INQUIRY" in json.loads(llm.requests[0].messages[-1].content)["allowedRoles"]
