"""Knowledge의 관계 조회(증상 문의의 확인 항목). 검색·관계 자료는 대역이다.

조회 결과가 직원에게 쓸 수 있는 항목만 State에 남는지, 조회 실패가 검색을 막지 않는지, 조회 조건이
정책과 예산을 지키는지를 본다. 관계가 실제로 좋은 질문을 만드는지는 검증하지 않는다.
"""

import pytest

from hops_ai.agents.knowledge import SearchKnowledgeAgent
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.roles import ProblemAnalysis
from hops_ai.harness.errors import OutputRejected, RetrievalUnavailable
from hops_ai.rules.intent_policy import INTENT_POLICIES
from hops_ai.services.retrieval.relation_source import (
    Basis,
    CheckItem,
    Question,
    RelationQuery,
    RelationResult,
    Symptom,
)
from hops_ai.supervisor.execution import execute_role
from tests.supervisor_fixtures import PROBLEM, SYSTEM, uid
from tests.unit.test_knowledge_agent import RecordingSource, services_for
from tests.unit.test_knowledge_assessment import DEFAULT_HITS
from tests.unit.test_knowledge_targets import state_for, symptom_request

FEATURE = uid(333)


def question(key="q-x-01", text="조회 기간을 어떻게 설정하셨나요?", visible=True) -> Question:
    return Question(id=key, catalogKey=key, text=text, basis=Basis(visible=visible))


def check(key="check-x", *, disclosable=True, scope="EMPLOYEE", questions=None, label=None):
    return CheckItem(
        id=key,
        catalogKey=key,
        label=label,
        employeeDisclosable=disclosable,
        accessScope=scope,
        basis=Basis(visible=True),
        questions=[question()] if questions is None else questions,
    )


def symptom(key="sym-x", *, scope="EMPLOYEE", checks=None) -> Symptom:
    return Symptom(
        id=key,
        catalogKey=key,
        label="문서가 목록에 보이지 않음",
        systemId=SYSTEM,
        featureId=FEATURE,
        featureCode="DOCUMENT_VIEW",
        accessScope=scope,
        basis=Basis(visible=True),
        score=0.8,
        checkItems=[check()] if checks is None else checks,
        causes=None,
    )


class FakeRelations:
    def __init__(self, *symptoms: Symptom, error: Exception | None = None) -> None:
        self.result = RelationResult(
            symptoms=tuple(symptoms), internal_judgement=False, employee_view=True
        )
        self.error = error
        self.queries: list[RelationQuery] = []

    async def search(self, query: RelationQuery) -> RelationResult:
        self.queries.append(query)
        if self.error is not None:
            raise self.error
        return self.result


def analysed(request, keywords=("문서", "목록"), features=()):
    state = state_for(request, keywords)
    analysis = ProblemAnalysis(
        problemRef=PROBLEM, keywords=list(keywords), featureCandidates=list(features)
    )
    return {**state, "analysis": {PROBLEM: analysis}}


async def lookup(relations, *, request=None, state=None, targets=(PROBLEM,), hits=DEFAULT_HITS):
    request = request or symptom_request()
    agent = SearchKnowledgeAgent(RecordingSource(hits), relation_source=relations)
    return await execute_role(
        state or analysed(request),
        AgentRole.KNOWLEDGE,
        services_for(request),
        {AgentRole.KNOWLEDGE: agent},
        targets=tuple(targets),
    )


# --- 조회 결과가 State에 남는 모양 ---


async def test_a_symptom_search_stores_the_employee_disclosable_check_items() -> None:
    relations = FakeRelations(symptom())

    merged = await lookup(relations)

    stored = merged["relations"][PROBLEM]
    assert stored.state == "FOUND"
    [item] = stored.symptoms
    assert [c.id for c in item.checkItems] == ["check-x"]
    assert item.checkItems[0].questions[0].text == "조회 기간을 어떻게 설정하셨나요?"
    assert not hasattr(item, "causes")


async def test_items_that_are_not_for_employees_are_dropped_even_if_the_source_returned_them() -> (
    None
):
    staff_check = check("check-staff", disclosable=False, label="담당자용 이름")
    blank_text = check("check-blank", questions=[question("q-blank", " ")])
    no_question = check("check-empty", questions=[])
    kept = check("check-ok", scope="SYSTEM_STAFF", questions=[question(visible=False)])
    relations = FakeRelations(
        symptom(checks=[staff_check, blank_text, no_question, kept]),
        symptom("sym-staff", scope="SYSTEM_STAFF"),
    )

    merged = await lookup(relations)

    [item] = merged["relations"][PROBLEM].symptoms
    assert [c.id for c in item.checkItems] == ["check-ok"]


async def test_a_symptom_whose_items_are_all_dropped_leaves_an_empty_result() -> None:
    relations = FakeRelations(symptom(checks=[check(disclosable=False)]))

    merged = await lookup(relations)

    assert merged["relations"][PROBLEM] == merged["relations"][PROBLEM].model_copy(
        update={"state": "EMPTY", "symptoms": []}
    )


async def test_no_symptom_is_recorded_as_empty_not_error() -> None:
    merged = await lookup(FakeRelations())

    assert merged["relations"][PROBLEM].state == "EMPTY"


# --- 조회 오류는 검색을 막지 않고 EMPTY와 구분된다 ---


async def test_a_lookup_error_is_recorded_as_error_and_the_search_result_stays() -> None:
    merged = await lookup(FakeRelations(error=RetrievalUnavailable()))

    assert merged["relations"][PROBLEM].state == "ERROR"
    assert merged["relations"][PROBLEM].symptoms == []
    assert any(r.search.state == "FOUND" for r in merged["roleResults"].values())


# --- 조회하는 조건 ---


async def test_the_query_carries_keywords_features_and_the_problems_system_only() -> None:
    request = symptom_request()
    relations = FakeRelations(symptom())

    await lookup(relations, request=request, state=analysed(request, features=(FEATURE,)))

    [query] = relations.queries
    assert query.keywords == ("문서", "목록") and query.feature_ids == {FEATURE}
    assert query.system_ids == {SYSTEM}
    assert query.allowed_scopes == {"EMPLOYEE"}
    assert query.run is not None


async def test_system_names_are_not_used_as_relation_keywords() -> None:
    request = symptom_request()
    names = [system.name for system in request.systems]
    relations = FakeRelations(symptom())

    await lookup(relations, request=request, state=analysed(request, (names[0], "문서")))

    assert relations.queries[0].keywords == ("문서",)


async def test_without_keywords_and_features_the_relations_are_not_looked_up() -> None:
    request = symptom_request()
    relations = FakeRelations(symptom())

    merged = await lookup(relations, request=request, state=analysed(request, keywords=()))

    assert relations.queries == [] and PROBLEM not in merged["relations"]


@pytest.mark.parametrize("intent", [i for i, p in INTENT_POLICIES.items() if not p.uses_relations])
async def test_intents_whose_policy_does_not_use_relations_never_look_them_up(intent) -> None:
    request = symptom_request(intent=intent)
    relations = FakeRelations(symptom())
    state = analysed(request)
    state["problems"][PROBLEM] = state["problems"][PROBLEM].model_copy(update={"intent": intent})

    try:
        await lookup(relations, request=request, state=state)
    except OutputRejected:
        pass  # 검색 자체를 하지 않는 의도도 있다

    assert relations.queries == []


def test_only_the_symptom_intent_uses_relations() -> None:
    assert {i for i, p in INTENT_POLICIES.items() if p.uses_relations} == {"OBSERVED_SYMPTOM"}


async def test_without_a_relation_source_or_targets_nothing_is_looked_up() -> None:
    request = symptom_request()
    relations = FakeRelations(symptom())
    state = analysed(request)
    no_source = SearchKnowledgeAgent(RecordingSource(DEFAULT_HITS))
    merged = await execute_role(
        state,
        AgentRole.KNOWLEDGE,
        services_for(request),
        {AgentRole.KNOWLEDGE: no_source},
        targets=(PROBLEM,),
    )
    assert merged["relations"] == {}

    role_level = SearchKnowledgeAgent(RecordingSource(DEFAULT_HITS), relation_source=relations)
    how_to = symptom_request(intent="HOW_TO", processing="KNOWLEDGE_QA")
    await execute_role(
        analysed(how_to),
        AgentRole.KNOWLEDGE,
        services_for(how_to),
        {AgentRole.KNOWLEDGE: role_level},
    )
    assert relations.queries == []


async def test_an_exhausted_tool_budget_skips_the_lookup_without_failing_the_run() -> None:
    request = symptom_request()
    services = services_for(request)
    budget = services.runtime.budget
    budget.max_tool_calls = 1
    budget.tool_calls_used = 1
    relations = FakeRelations(symptom())
    agent = SearchKnowledgeAgent(RecordingSource(DEFAULT_HITS), relation_source=relations)

    merged = await execute_role(
        analysed(request),
        AgentRole.KNOWLEDGE,
        services,
        {AgentRole.KNOWLEDGE: agent},
        targets=(PROBLEM,),
    )

    assert relations.queries == [] and PROBLEM not in merged["relations"]


async def test_the_search_error_path_does_not_look_up_relations() -> None:
    request = symptom_request()
    relations = FakeRelations(symptom())
    agent = SearchKnowledgeAgent(
        RecordingSource(error=RetrievalUnavailable()), relation_source=relations
    )

    with pytest.raises(RetrievalUnavailable):  # 검색 오류는 지금처럼 병합에서 오류로 전해진다
        await execute_role(
            analysed(request),
            AgentRole.KNOWLEDGE,
            services_for(request),
            {AgentRole.KNOWLEDGE: agent},
            targets=(PROBLEM,),
        )

    assert relations.queries == []


class Reserving:
    """조회 대신 실행 문맥의 도구 예약만 해 보는 대역."""

    async def search(self, query):
        query.run.reserve_call()
        return RelationResult(symptoms=(), internal_judgement=False)


async def reserve_with(allowed: frozenset[str]):
    from hops_ai.harness.policy import ToolPolicy

    request = symptom_request()
    services = services_for(request)
    services.runtime.tool_policy = ToolPolicy.of({AgentRole.KNOWLEDGE: allowed})
    agent = SearchKnowledgeAgent(RecordingSource(DEFAULT_HITS), relation_source=Reserving())
    await execute_role(
        analysed(request),
        AgentRole.KNOWLEDGE,
        services,
        {AgentRole.KNOWLEDGE: agent},
        targets=(PROBLEM,),
    )
    return services.runtime.budget


async def test_the_lookup_spends_one_tool_call_when_the_relation_tool_is_allowed() -> None:
    budget = await reserve_with(frozenset({"search_relations"}))

    assert budget.tool_calls_used == 1


async def test_the_lookup_is_refused_when_only_the_search_tool_is_allowed() -> None:
    from hops_ai.harness.errors import PolicyDenied

    with pytest.raises(PolicyDenied):
        await reserve_with(frozenset({"search_knowledge"}))


# --- 병합과 연결 ---


def test_a_relation_result_for_an_unknown_problem_is_rejected_by_the_merge() -> None:
    from hops_ai.contracts.roles import KnowledgeResult, RelationNeighbors, SearchResult
    from hops_ai.supervisor.merge import merge_knowledge_result

    request = symptom_request()
    state = analysed(request)
    result = KnowledgeResult(
        invocationId="x",
        search=SearchResult(state="EMPTY", evidence=[], error=None),
        guide=None,
        problemRef="new:404",
        relations=RelationNeighbors(state="EMPTY", symptoms=[]),
    )

    with pytest.raises(OutputRejected):
        merge_knowledge_result(state, result, authorized_refs=[], verified_evidence=())


def test_the_registry_and_pipeline_hand_the_relation_source_to_knowledge() -> None:
    from hops_ai.agents.registry import build_registry
    from hops_ai.services.runs.supervisor import SupervisorPipeline
    from tests.doubles.llm import FakeLLMClient

    relations = FakeRelations()
    registry = build_registry(RecordingSource(), relation_source=relations)
    assert registry[AgentRole.KNOWLEDGE]._relation_source is relations  # noqa: SLF001
    assert build_registry(RecordingSource())[AgentRole.KNOWLEDGE]._relation_source is None  # noqa: SLF001

    pipeline = SupervisorPipeline(
        FakeLLMClient(),
        knowledge_source=RecordingSource(),
        assess_evidence=True,
        relation_source=relations,
    )
    agent = pipeline._supervisor._agents[AgentRole.KNOWLEDGE]  # noqa: SLF001
    assert agent._relation_source is relations  # noqa: SLF001


def test_the_factory_connects_relations_only_for_the_spring_source() -> None:
    from hops_ai.services.retrieval.factory import relation_source_for
    from hops_ai.services.retrieval.spring_source import SpringRelationSource
    from hops_ai.settings import KnowledgeSourceKind, Settings

    assert relation_source_for(Settings(_env_file=None)) is None
    fixture = Settings(_env_file=None, knowledge_source=KnowledgeSourceKind.FIXTURE)
    assert relation_source_for(fixture) is None
    from tests.unit.test_spring_source import settings

    spring = settings(knowledge_source=KnowledgeSourceKind.SPRING)
    assert isinstance(relation_source_for(spring), SpringRelationSource)
