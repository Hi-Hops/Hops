"""Knowledge 역할의 질의 구성·검색 상태 구분·안내 구성. 모델을 쓰지 않고 검색 자료는 대역이다.

실행기(execute_role)를 거쳐 병합 검증까지 통과하는지 확인한다. 고정 자료의 검색 품질이나
Spring 권한 서버 연동은 검증하지 않는다.
"""

from collections.abc import Sequence

import pytest

from hops_ai.agents.base import PendingAgent
from hops_ai.agents.knowledge import SearchKnowledgeAgent
from hops_ai.agents.registry import build_registry
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.execution import RunKind
from hops_ai.contracts.wire import AiRunRequest
from hops_ai.harness.errors import InputRejected, OutputRejected, RetrievalUnavailable
from hops_ai.services.retrieval.fixture_source import FixtureKnowledgeSource
from hops_ai.services.retrieval.knowledge_source import KnowledgeHit, KnowledgeQuery
from hops_ai.services.shared import SharedServices
from hops_ai.services.tools.disabled import DisabledToolGateway
from hops_ai.settings import Settings
from hops_ai.supervisor.bootstrap import create_run_services
from hops_ai.supervisor.execution import RoleRequest, execute_role
from tests.doubles.llm import FakeLLMClient
from tests.supervisor_fixtures import (
    OTHER_SYSTEM,
    PROBLEM,
    SYSTEM,
    authorize,
    make_evidence,
    make_request,
    reserve,
    running_state,
    uid,
)

HOW_TO = "전자결재 문서 조회 방법을 알려주세요"


def how_to_request(
    text: str = HOW_TO,
    *,
    scopes: tuple[str, ...] = ("EMPLOYEE",),
    purpose: str = "EMPLOYEE_HELP",
    system_ids: tuple[str, ...] = (SYSTEM, OTHER_SYSTEM),
    processing_type: str = "KNOWLEDGE_QA",
    reported_system: str | None = SYSTEM,
    summary: str = "문서 조회 방법 문의",
) -> AiRunRequest:
    fields = make_request().model_dump(mode="json")
    fields["messages"][0]["content"] = text
    fields["problems"][0].update(
        summary=summary,
        intent="HOW_TO",
        processingType=processing_type,
        reportedSystemId=reported_system,
    )
    fields["accessContext"].update(
        purpose=purpose, allowedDocumentScopes=list(scopes), systemIds=list(system_ids)
    )
    return AiRunRequest.model_validate(fields)


def services_for(request: AiRunRequest) -> SharedServices:
    return create_run_services(
        request,
        settings=Settings(_env_file=None),
        access_scope_ref=f"test-scope:{request.runId}",
        llm=FakeLLMClient(),
        tools=DisabledToolGateway(),
    )


class RecordingSource:
    """받은 질의를 기록하고 정해진 결과나 오류를 돌려주는 검색 자료 대역."""

    def __init__(self, hits: Sequence[KnowledgeHit] = (), error: Exception | None = None) -> None:
        self.hits = list(hits)
        self.error = error
        self.queries: list[KnowledgeQuery] = []

    async def search(self, query: KnowledgeQuery) -> Sequence[KnowledgeHit]:
        self.queries.append(query)
        if self.error is not None:
            raise self.error
        return self.hits


async def run_through_executor(request: AiRunRequest, source):
    """실행기로 Knowledge를 한 번 호출하고 병합된 State를 돌려준다."""
    state = running_state(request)
    agents = {AgentRole.KNOWLEDGE: SearchKnowledgeAgent(source)}
    return await execute_role(state, AgentRole.KNOWLEDGE, services_for(request), agents)


async def test_how_to_question_ends_with_a_guide_built_from_the_approved_body() -> None:
    state = await run_through_executor(how_to_request(), FixtureKnowledgeSource())

    guide = state["guide"]
    assert guide is not None and guide.problemRef == PROBLEM
    [evidence] = state["evidence"].values()
    assert evidence.kind == "GUIDE" and evidence.origin == "SYNTHETIC"
    assert guide.evidenceIds == [evidence.id]
    assert "'문서 조회'를 선택합니다" in guide.messageText
    assert guide.messageText.endswith(f"참고 자료: {evidence.title}")


async def test_query_uses_the_summary_recent_user_messages_and_only_the_problem_system() -> None:
    source = RecordingSource()
    request = how_to_request()
    fields = request.model_dump(mode="json")
    older = [
        {**fields["messages"][0], "id": uid(500 + n), "sequenceNo": n + 2, "content": f"추가{n}"}
        for n in range(6)
    ]
    ai = {
        **fields["messages"][0],
        "id": uid(600),
        "sequenceNo": 20,
        "sender": "AI",
        "content": "AI",
    }
    fields["messages"] = [fields["messages"][0], *older, ai]
    request = AiRunRequest.model_validate(fields)

    await run_through_executor(request, source)

    [query] = source.queries
    lines = query.text.split("\n")
    assert lines[0] == "문서 조회 방법 문의"
    # 요약 다음에 가장 최근 사용자 메시지 5개만, AI 메시지는 제외한다.
    assert lines[1:] == [f"추가{n}" for n in range(1, 6)]
    assert query.system_ids == frozenset({SYSTEM})
    assert query.allowed_scopes == frozenset({"EMPLOYEE"})
    assert dict(query.system_ids_by_code) == {
        "ELECTRONIC_APPROVAL": SYSTEM,
        "COMMON_LOGIN": OTHER_SYSTEM,
    }


async def test_query_scope_is_exactly_the_scope_the_server_allowed() -> None:
    source = RecordingSource()
    request = how_to_request(scopes=("EMPLOYEE", "SYSTEM_STAFF"), purpose="SYSTEM_WORK")

    await run_through_executor(request, source)

    assert source.queries[0].allowed_scopes == frozenset({"EMPLOYEE", "SYSTEM_STAFF"})


async def test_employee_help_input_with_staff_scope_is_rejected_before_any_search() -> None:
    source = RecordingSource()

    with pytest.raises(InputRejected):
        await run_through_executor(how_to_request(scopes=("EMPLOYEE", "SYSTEM_STAFF")), source)

    assert source.queries == []


async def test_staff_work_may_search_staff_scope_but_guide_uses_employee_guides_only() -> None:
    request = how_to_request(
        "문서 조회 방법과 미표시 점검", scopes=("EMPLOYEE", "SYSTEM_STAFF"), purpose="SYSTEM_WORK"
    )

    state = await run_through_executor(request, FixtureKnowledgeSource())

    kinds = {item.kind for item in state["evidence"].values()}
    assert kinds == {"GUIDE", "RUNBOOK"}
    guide = state["guide"]
    assert guide is not None
    assert all(state["evidence"][item].accessScope == "EMPLOYEE" for item in guide.evidenceIds)
    assert "점검 순서" not in guide.messageText


async def test_no_result_is_empty_and_creates_no_guide() -> None:
    request = how_to_request("점심 메뉴 추천", summary="점심 메뉴 추천 요청")

    state = await run_through_executor(request, FixtureKnowledgeSource())

    assert state["guide"] is None
    assert state["evidence"] == {}


async def test_search_error_is_not_turned_into_an_empty_result() -> None:
    source = RecordingSource(error=RetrievalUnavailable())

    with pytest.raises(RetrievalUnavailable):
        await run_through_executor(how_to_request(), source)


async def test_agent_returns_an_error_result_with_no_evidence_or_guide() -> None:
    request = how_to_request()
    state = reserve(running_state(request), AgentRole.KNOWLEDGE, "inv-1")
    agent = SearchKnowledgeAgent(RecordingSource(error=RetrievalUnavailable()))

    response = await agent.run(RoleRequest("inv-1", state), services_for(request))

    search = response.result.search
    assert search.state == "ERROR" and search.evidence == []
    assert search.error is not None and search.error.retryable
    assert response.result.guide is None
    assert response.authorized_refs == () and response.verified_evidence == ()


async def test_staff_only_hit_never_becomes_a_guide() -> None:
    runbook = make_evidence(kind="RUNBOOK", accessScope="SYSTEM_STAFF", title="내부 점검")
    hit = KnowledgeHit(
        evidence=runbook,
        reference=authorize(runbook),
        content="담당자만 보는 점검 순서",
        score=1.0,
    )
    request = how_to_request(scopes=("EMPLOYEE", "SYSTEM_STAFF"), purpose="SYSTEM_WORK")

    state = await run_through_executor(request, RecordingSource([hit]))

    assert state["guide"] is None
    assert list(state["evidence"]) == [runbook.id]


async def test_source_returning_out_of_scope_evidence_is_rejected_by_the_merge() -> None:
    other = make_evidence(systemId=OTHER_SYSTEM, id=uid(40), documentId=uid(41))
    hit = KnowledgeHit(
        evidence=other, reference=authorize(other), content="다른 시스템 안내", score=1.0
    )
    # 접근 범위에 없는 시스템의 근거가 섞이면 병합 단계가 거부한다.
    request = how_to_request(system_ids=(SYSTEM,))

    with pytest.raises(OutputRejected):
        await run_through_executor(request, RecordingSource([hit]))


@pytest.mark.parametrize(
    "overrides",
    [
        {"processing_type": "INVESTIGATION"},
        {"reported_system": None},
    ],
)
async def test_problem_that_cannot_be_guided_is_rejected_instead_of_returning_empty(
    overrides,
) -> None:
    source = RecordingSource()

    with pytest.raises(OutputRejected):
        await run_through_executor(how_to_request(**overrides), source)

    assert source.queries == []


async def test_second_guide_is_not_created_when_one_exists() -> None:
    request = how_to_request()
    first = await run_through_executor(request, FixtureKnowledgeSource())
    source = RecordingSource()
    agent = SearchKnowledgeAgent(source)
    state = reserve(first, AgentRole.KNOWLEDGE, "inv-2")

    with pytest.raises(OutputRejected):
        await agent.run(RoleRequest("inv-2", state), services_for(request))

    assert source.queries == []


async def test_case_draft_run_is_not_served() -> None:
    request = make_request(kind=RunKind.CASE_DRAFT)
    state = reserve(running_state(request), AgentRole.KNOWLEDGE, "inv-1")

    with pytest.raises(OutputRejected):
        await SearchKnowledgeAgent(RecordingSource()).run(
            RoleRequest("inv-1", state), services_for(request)
        )


def test_default_registry_still_keeps_knowledge_unconnected() -> None:
    assert isinstance(build_registry()[AgentRole.KNOWLEDGE], PendingAgent)
