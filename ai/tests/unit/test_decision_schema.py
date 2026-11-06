"""판단 요청이 허용 역할만 열거한 출력 스키마를 싣는지.

실제 모델 시험에서 모델이 허용 역할에 없는 Inquiry를 그래도 골라 실행이 거부됐다. 허용되지 않은
역할은 출력 단계에서 고를 수 없게 한다. 모델은 Fake이므로 실제 모델이 이 스키마를 지키는 것은
공급자의 구조화 출력 기능에 맡기며 여기서 검증하지 않는다.
"""

import json

import pytest

from hops_ai.agents.inquiry.extraction import QuotedValue
from hops_ai.contracts.domain import AgentRole
from hops_ai.services.retrieval.fixture_source import FixtureKnowledgeSource
from hops_ai.supervisor.decision import SupervisorDecision, decision_schema
from hops_ai.supervisor.graph import Supervisor
from tests.doubles.agents import runtime_registry, ticket_decisions
from tests.doubles.llm import FakeLLMClient
from tests.supervisor_fixtures import MESSAGE
from tests.unit.test_inquiry_agent import _strict_violations
from tests.unit.test_knowledge_empty_handoff import (
    how_to_extraction,
    no_guide_request,
    services_for,
)
from tests.unit.test_knowledge_wiring import decision, how_to_first_message


def role_enum(schema) -> list[str]:
    [string, null] = schema["properties"]["role"]["anyOf"]
    assert null == {"type": "null"}
    return string["enum"]


def test_schema_lists_exactly_the_allowed_roles() -> None:
    schema = decision_schema(["KNOWLEDGE", "TICKET"])

    assert role_enum(schema) == ["KNOWLEDGE", "TICKET"]
    assert "INQUIRY" not in json.dumps(schema)


def test_schema_without_any_allowed_role_only_allows_null() -> None:
    schema = decision_schema([])

    assert schema["properties"]["role"] == {"type": "null"}


@pytest.mark.parametrize("roles", [["INQUIRY", "KNOWLEDGE", "TICKET"], ["TICKET"], []])
def test_restricted_schema_keeps_the_strict_structured_output_shape(roles) -> None:
    schema = decision_schema(roles)

    assert _strict_violations(schema) == []
    assert set(schema["required"]) == {"nextAction", "role", "publicSummary", "replyKind"}
    assert "$defs" not in schema and "$ref" not in json.dumps(schema)


def test_default_model_schema_is_not_modified_by_building_a_restricted_one() -> None:
    before = SupervisorDecision.model_json_schema()

    decision_schema(["TICKET"])

    assert SupervisorDecision.model_json_schema() == before
    assert "$defs" in before


async def test_first_request_offers_only_the_roles_the_context_allows() -> None:
    request = how_to_first_message()
    llm = FakeLLMClient(decision("FINISH"))
    agents = runtime_registry(FixtureKnowledgeSource())
    supervisor = Supervisor(agents, restrict_roles=True)

    await supervisor.analyze(request, services_for(request, llm))

    sent = llm.requests[0]
    context = json.loads(sent.messages[-1].content)
    # 문제가 없는 첫 판단에서는 검색할 문제도 티켓으로 제안할 문제도 없어 Inquiry만 남는다.
    assert context["allowedRoles"] == ["INQUIRY"]
    assert role_enum(sent.response_schema) == ["INQUIRY"]
    assert sent.response_model is SupervisorDecision
    assert sent.prompt_version == "supervisor.decision.v10"


async def test_schema_after_an_empty_search_leaves_out_inquiry_when_nothing_is_missing() -> None:
    request = no_guide_request()
    quote = QuotedValue(messageId=MESSAGE, quote="도장 이미지 등록")
    llm = FakeLLMClient(
        decision("CALL_ROLE", AgentRole.INQUIRY),
        how_to_extraction(desired=quote),
        decision("CALL_ROLE", AgentRole.KNOWLEDGE),
        decision("CALL_ROLE", AgentRole.TICKET),
        ticket_decisions("new:1"),
        decision("PROPOSE_TICKETS"),
    )
    agents = runtime_registry(FixtureKnowledgeSource())
    supervisor = Supervisor(agents, restrict_roles=True)

    result = await supervisor.analyze(request, services_for(request, llm))

    assert result.status == "SUCCEEDED", result.error
    after_empty = llm.requests[3]
    allowed = json.loads(after_empty.messages[-1].content)["allowedRoles"]
    # 검색한 문제는 같은 질의로 다시 검색하지 않으므로 Knowledge도 선택지에서 빠진다.
    assert role_enum(after_empty.response_schema) == allowed == ["TICKET"]
    # 검색 직전의 판단에서는 Inquiry와 Knowledge가 선택지에 있다. 사용법 문제는 검색하지 않으면
    # 티켓으로 넘길 수 없으므로 Ticket은 아직 없고, 스키마가 이후 상태를 반영해 좁아진다.
    assert role_enum(llm.requests[2].response_schema) == ["INQUIRY", "KNOWLEDGE"]
