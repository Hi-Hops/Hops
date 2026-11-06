"""문제별 행동 경로의 검색: 대상 전달, 지식 종류 필터, 재검색 질의 후보.

모델·실제 검색은 쓰지 않는다. 검색 자료는 질의를 기록하는 대역이고, 고정 자료와 Spring 요청 본문은
각각 종류가 질의에서 요청까지 전달되는지만 확인한다. 검색 품질은 검증하지 않는다.
"""

import hashlib
import json

import pytest

from hops_ai.agents.knowledge import SearchKnowledgeAgent
from hops_ai.agents.knowledge.agent import (
    candidate_queries,
    next_query,
    research_used,
)
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.roles import KnowledgeResult, ProblemAnalysis, SearchResult
from hops_ai.contracts.wire import AiRunRequest
from hops_ai.harness.errors import OutputRejected
from hops_ai.rules.intent_policy import search_kinds
from hops_ai.services.retrieval.fixture_source import FixtureKnowledgeSource
from hops_ai.services.retrieval.knowledge_source import KnowledgeQuery
from hops_ai.supervisor.execution import execute_role
from tests.supervisor_fixtures import (
    OTHER_SYSTEM,
    PROBLEM,
    SYSTEM,
    make_details,
    running_state,
)
from tests.unit.test_knowledge_agent import RecordingSource, how_to_request, services_for
from tests.unit.test_spring_source import knowledge_body, knowledge_query, source_returning

EMPLOYEE = frozenset({"EMPLOYEE"})
STAFF = frozenset({"EMPLOYEE", "SYSTEM_STAFF"})


def symptom_request(
    *, intent="OBSERVED_SYMPTOM", processing="INVESTIGATION", staff=False, system=SYSTEM
) -> AiRunRequest:
    request = how_to_request(
        scopes=("EMPLOYEE", "SYSTEM_STAFF") if staff else ("EMPLOYEE",),
        purpose="SYSTEM_WORK" if staff else "EMPLOYEE_HELP",
    )
    fields = request.model_dump(mode="json")
    fields["problems"][0].update(
        intent=intent, processingType=processing, summary="문서 미표시", reportedSystemId=system
    )
    return AiRunRequest.model_validate(fields)


def state_for(request, keywords=(), *, details=False):
    state = running_state(request)
    problem = state["problems"][PROBLEM]
    if details:
        problem = problem.model_copy(update={"details": make_details()})
    return {
        **state,
        "problems": {**state["problems"], PROBLEM: problem},
        "analysis": {
            PROBLEM: ProblemAnalysis(
                problemRef=PROBLEM, keywords=list(keywords), featureCandidates=[]
            )
        },
    }


async def search_once(state, request, source, *, targets=(PROBLEM,)):
    agents = {AgentRole.KNOWLEDGE: SearchKnowledgeAgent(source)}
    return await execute_role(
        state, AgentRole.KNOWLEDGE, services_for(request), agents, targets=tuple(targets)
    )


# --- 검색 종류 ---


def test_search_kinds_drop_staff_only_kinds_outside_the_staff_scope() -> None:
    assert search_kinds("OBSERVED_SYMPTOM", EMPLOYEE) == {"GUIDE", "CASE"}
    assert search_kinds("OBSERVED_SYMPTOM", STAFF) == {"GUIDE", "RUNBOOK", "CASE"}
    assert search_kinds("HOW_TO", EMPLOYEE) == {"GUIDE", "POLICY"}
    assert search_kinds("ACCESS_REQUEST", ["EMPLOYEE"]) == {"GUIDE", "POLICY"}


def test_intents_without_search_kinds_never_search_in_any_scope() -> None:
    for intent in ("FEATURE_REQUEST", "IMPROVEMENT_REQUEST", "UNKNOWN", "OUT_OF_SCOPE"):
        assert search_kinds(intent, STAFF) == frozenset()


# --- 질의의 종류와 지문 ---


def legacy_digest(query: KnowledgeQuery) -> str:
    canonical = json.dumps(
        {
            "text": query.text,
            "scopes": sorted(query.allowed_scopes),
            "systems": sorted(query.system_ids),
            "topK": query.top_k,
        },
        ensure_ascii=False,
        sort_keys=True,
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def test_a_query_without_kinds_keeps_the_digest_it_had_before_kinds_existed() -> None:
    query = knowledge_query()

    assert query.kinds is None
    assert query.digest() == legacy_digest(query)


def test_kinds_change_the_digest_and_their_order_does_not() -> None:
    plain = knowledge_query()
    guide = knowledge_query(kinds=frozenset({"GUIDE"}))
    both = knowledge_query(kinds=frozenset({"GUIDE", "CASE"}))

    assert len({plain.digest(), guide.digest(), both.digest()}) == 3
    assert both.digest() == knowledge_query(kinds=frozenset({"CASE", "GUIDE"})).digest()


@pytest.mark.parametrize("kinds", [frozenset(), frozenset({"NOTE"}), frozenset({"GUIDE", "NOTE"})])
def test_kinds_must_be_a_non_empty_subset_of_the_known_kinds(kinds) -> None:
    with pytest.raises(ValueError):
        knowledge_query(kinds=kinds)


async def test_the_fixture_source_filters_by_kind() -> None:
    def query(kinds):
        return KnowledgeQuery(
            text="문서 조회 방법과 미표시 점검",
            allowed_scopes=STAFF,
            system_ids=frozenset({SYSTEM}),
            system_ids_by_code={"ELECTRONIC_APPROVAL": SYSTEM},
            kinds=kinds,
        )

    source = FixtureKnowledgeSource()

    async def kinds_found(kinds):
        return {hit.evidence.kind for hit in await source.search(query(kinds))}

    assert await kinds_found(None) == {"GUIDE", "RUNBOOK"}
    assert await kinds_found(frozenset({"RUNBOOK"})) == {"RUNBOOK"}
    assert await kinds_found(frozenset({"GUIDE"})) == {"GUIDE"}
    assert await kinds_found(frozenset({"POLICY"})) == frozenset()


async def test_the_spring_request_carries_the_kinds_only_when_they_are_given() -> None:
    captured: list = []
    source = source_returning(200, knowledge_body("NO_RESULT"), captured=captured)

    await source.search(knowledge_query(kinds=frozenset({"CASE", "GUIDE"})))
    await source.search(knowledge_query())

    assert json.loads(captured[0].content)["kinds"] == ["CASE", "GUIDE"]
    assert "kinds" not in json.loads(captured[1].content)


# --- 대상 전달 ---


async def test_targets_search_that_problem_with_the_kinds_the_scope_allows() -> None:
    request = symptom_request()
    source = RecordingSource()

    await search_once(state_for(request), request, source)

    [query] = source.queries
    assert query.kinds == {"GUIDE", "CASE"}


async def test_a_staff_scope_run_also_asks_for_runbooks() -> None:
    request = symptom_request(staff=True)
    source = RecordingSource()

    await search_once(state_for(request), request, source)

    assert source.queries[0].kinds == {"GUIDE", "RUNBOOK", "CASE"}


async def test_without_targets_the_role_still_picks_its_own_how_to_problem_without_kinds() -> None:
    request = how_to_request()
    source = RecordingSource()
    agents = {AgentRole.KNOWLEDGE: SearchKnowledgeAgent(source)}

    await execute_role(running_state(request), AgentRole.KNOWLEDGE, services_for(request), agents)

    assert source.queries[0].kinds is None


@pytest.mark.parametrize(
    "request_fields",
    [
        {"intent": "FEATURE_REQUEST", "processing": "CHANGE_REQUEST"},  # 검색하지 않는 의도
        {"system": None},  # 시스템 미확정
    ],
)
async def test_a_problem_that_cannot_be_searched_is_rejected_not_searched(request_fields) -> None:
    request = symptom_request(**request_fields)
    source = RecordingSource()

    with pytest.raises(OutputRejected):
        await search_once(state_for(request), request, source)

    assert source.queries == []


async def test_targets_must_be_one_existing_problem() -> None:
    request = symptom_request()
    source = RecordingSource()

    for targets in (("new:9",), (PROBLEM, "new:1")):
        with pytest.raises(OutputRejected):
            await search_once(state_for(request), request, source, targets=targets)
    assert source.queries == []


# --- 재검색 질의 후보 ---


def first_problem(state):
    return state["problems"][PROBLEM]


async def test_research_tries_keywords_then_keywords_with_details_then_runs_out() -> None:
    request = symptom_request()
    state = state_for(request, ["전자결재", "문서", "미표시"], details=True)
    source = RecordingSource()

    for step in range(3):
        # 첫 검색은 재검색이 아니다. 검색이 k번 끝난 뒤 재검색 횟수는 max(0, k - 1)이다.
        assert research_used(state, PROBLEM) == max(0, step - 1)
        state = await search_once(state, request, source)
    assert research_used(state, PROBLEM) == 2

    first, keywords, with_details = (query.text for query in source.queries)
    assert keywords == "문서 미표시"  # 시스템 이름은 뺀다
    assert with_details.startswith("문서 미표시\n") and "첨부파일 열람" in with_details
    assert first != keywords
    assert next_query(state, first_problem(state)) is None
    with pytest.raises(OutputRejected):
        await search_once(state, request, source)
    assert len(source.queries) == 3


async def test_the_three_queries_have_different_digests_so_none_is_repeated() -> None:
    request = symptom_request()
    state = state_for(request, ["문서", "미표시"], details=True)

    queries = candidate_queries(state, first_problem(state))

    assert len(queries) == 3
    assert len({query.digest() for query in queries}) == 3
    assert all(query.kinds == {"GUIDE", "CASE"} for query in queries)


def test_without_usable_keywords_only_the_first_query_exists() -> None:
    request = symptom_request()
    for keywords in ([], ["전자결재"], ["공통 로그인"], ["전자결재문서"]):
        state = state_for(request, keywords, details=True)

        assert len(candidate_queries(state, first_problem(state))) == 1, keywords


def test_system_names_and_codes_are_not_search_keywords_but_other_words_are() -> None:
    request = symptom_request()
    state = state_for(request, ["전자결재", "공통 로그인", "로그인", "문서"])

    keyword_query = candidate_queries(state, first_problem(state))[1]

    assert keyword_query.text == "로그인 문서"
    assert OTHER_SYSTEM  # 공통 로그인은 두 번째 시스템의 이름이다


def test_a_problem_that_does_not_search_has_no_queries() -> None:
    request = symptom_request(intent="FEATURE_REQUEST", processing="CHANGE_REQUEST")
    state = state_for(request, ["문서"])

    assert candidate_queries(state, first_problem(state)) == []
    assert next_query(state, first_problem(state)) is None


def test_a_search_result_without_a_digest_blocks_further_searches_of_that_problem() -> None:
    request = symptom_request()
    state = state_for(request, ["문서"])
    unknown = KnowledgeResult(
        invocationId="earlier",
        search=SearchResult(state="EMPTY", evidence=[], error=None),
        guide=None,
        problemRef=PROBLEM,
        queryDigest=None,
    )
    state = {**state, "roleResults": {"earlier": unknown}}

    assert next_query(state, first_problem(state)) is None


async def test_the_first_query_of_the_problem_path_is_not_the_one_of_the_role_path() -> None:
    request = symptom_request()
    state = state_for(request, ["문서"])
    source = RecordingSource()

    await search_once(state, request, source)

    [query] = source.queries
    assert query.digest() != legacy_digest(query)  # 종류가 더해져 다른 질의다
