"""가이드를 찾지 못한 사용법 문제가 담당자 전달 정보를 갖췄는지를 코드가 세어 Supervisor에 알린다.

실제 모델 시험에서 정보가 이미 충분한데도 Supervisor가 Inquiry를 반복 호출해 모델 호출 한도를
넘겼다. 모델은 "충분한가"를 추측해야 했다. 이 값은 사실만 알리고 역할 선택을 제한하지 않는다.
모델은 Fake이므로 모델이 이 값을 어떻게 쓰는지는 검증하지 않는다.
"""

import json

import pytest

from hops_ai.agents.inquiry.readiness import get_handoff_readiness
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.wire import RequestDetails
from hops_ai.supervisor.decision import _decision_context
from hops_ai.supervisor.graph import Supervisor
from tests.doubles.llm import FakeLLMClient
from tests.supervisor_fixtures import make_details
from tests.unit.test_knowledge_empty_handoff import (
    empty_search,
    error_search,
    how_to_problem,
    services_for,
    state_after,
)
from tests.unit.test_knowledge_wiring import decision


def details(**values) -> RequestDetails:
    blank = {name: None for name in RequestDetails.model_fields if name != "acceptanceCriteria"}
    return make_details().model_copy(update={**blank, **values})


def how_to(**values):
    return how_to_problem(details=details(**values))


def test_nothing_is_reported_before_the_search_finds_nothing() -> None:
    assert get_handoff_readiness(state_after(None, how_to(taskOrScreen="문서 조회"))) == []


def test_search_error_is_not_treated_as_nothing_found() -> None:
    state = state_after(error_search(), how_to(taskOrScreen="문서 조회"))

    assert get_handoff_readiness(state) == []


def test_missing_items_are_counted_by_the_form_rule() -> None:
    state = state_after(empty_search(), how_to(taskOrScreen="결재선 변경"))

    [item] = get_handoff_readiness(state)

    assert item["problemRef"] == "new:1"
    assert item["missingForHandoff"] == ["expectedBehavior"]
    assert item["remainingQuestions"] == 5


def test_complete_information_reports_nothing_missing() -> None:
    state = state_after(
        empty_search(),
        how_to(taskOrScreen="결재선 변경", expectedBehavior="한꺼번에 바꾸고 싶어요"),
    )

    [item] = get_handoff_readiness(state)

    assert item["missingForHandoff"] == []


def test_item_the_user_said_they_do_not_know_is_not_missing() -> None:
    problem = how_to(taskOrScreen="결재선 변경").model_copy(update={"unknowns": ["기대한 동작"]})
    state = state_after(empty_search(), problem)

    [item] = get_handoff_readiness(state)

    assert item["missingForHandoff"] == []


def test_problem_without_a_system_reports_the_system_as_missing() -> None:
    problem = how_to(taskOrScreen="결재선 변경", expectedBehavior="바꾸기").model_copy(
        update={"reportedSystemCandidateId": None}
    )
    state = state_after(empty_search(), problem)

    [item] = get_handoff_readiness(state)

    assert item["missingForHandoff"] == ["system"]


@pytest.mark.parametrize("processing_type", ["INVESTIGATION", "REVIEW_REQUIRED"])
def test_only_how_to_problems_are_reported(processing_type) -> None:
    problem = how_to(taskOrScreen="결재선 변경").model_copy(
        update={"processingType": processing_type}
    )

    assert get_handoff_readiness(state_after(empty_search(), problem)) == []


def test_decision_context_carries_the_readiness_without_limiting_roles() -> None:
    state = state_after(empty_search(), how_to(taskOrScreen="결재선 변경"))

    context = _decision_context(state)

    assert context["handoffReadiness"] == [
        {"problemRef": "new:1", "missingForHandoff": ["expectedBehavior"], "remainingQuestions": 5}
    ]
    assert context["allowedRoles"] == ["INQUIRY", "KNOWLEDGE", "TICKET"]


async def test_supervisor_sees_the_readiness_and_the_v5_guidance_after_an_empty_search() -> None:
    from hops_ai.services.retrieval.fixture_source import FixtureKnowledgeSource
    from tests.doubles.agents import runtime_registry, ticket_decisions
    from tests.unit.test_knowledge_empty_handoff import how_to_extraction, no_guide_request

    request = no_guide_request()
    llm = FakeLLMClient(
        decision("CALL_ROLE", AgentRole.INQUIRY),
        how_to_extraction(),
        decision("CALL_ROLE", AgentRole.KNOWLEDGE),
        decision("CALL_ROLE", AgentRole.TICKET),
        ticket_decisions("new:1"),
        decision("PROPOSE_TICKETS"),
    )
    registry = runtime_registry(FixtureKnowledgeSource())

    result = await Supervisor(registry).analyze(request, services_for(request, llm))

    assert result.status == "SUCCEEDED"
    after_empty = llm.requests[3]
    context = json.loads(after_empty.messages[-1].content)
    [item] = context["handoffReadiness"]
    assert item["missingForHandoff"] == ["expectedBehavior"]
    assert "handoffReadiness" in after_empty.messages[0].content
    assert after_empty.prompt_version == "supervisor.decision.v10"
    # 검색 전에는 알릴 것이 없다.
    assert json.loads(llm.requests[2].messages[-1].content)["handoffReadiness"] == []
