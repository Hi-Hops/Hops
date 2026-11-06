"""평가용 관계 검색원과 관계 조회 기록. Spring 관계 조회의 근사이며 실조회가 아니다."""

import json

import pytest

from evaluation.harness.catalog import SYSTEMS
from evaluation.harness.corpus_relations import (
    FEATURE_NAMES,
    CorpusRelationError,
    CorpusRelationSource,
)
from evaluation.harness.recorders import CallLog, RecordingAgent
from evaluation.harness.trace import RoleCall
from evaluation.harness.trace_io import trace_from_dict, trace_to_dict
from evaluation.pilot import (
    CORPUS_RELATION_SOURCE_NAME,
    _build_real,
    _turn_summary,
    run_metadata,
)
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.roles import (
    KnowledgeResult,
    RelationCheck,
    RelationNeighbors,
    RelationQuestion,
    RelationSymptom,
    SearchResult,
)
from hops_ai.services.retrieval.relation_source import RelationQuery
from tests.eval_fixtures import ai_result, request_with, run_trace, turn

EA = SYSTEMS[0]
LOGIN = SYSTEMS[1]
VIEW_FEATURE = next(f["id"] for f in EA["features"] if f["name"] == "문서 조회")


def query(keywords=("문서", "목록"), features=(VIEW_FEATURE,), system=EA, scopes=("EMPLOYEE",)):
    return RelationQuery(
        keywords=tuple(keywords),
        feature_ids=frozenset(features),
        system_ids=frozenset({system["id"]}),
        allowed_scopes=frozenset(scopes),
    )


async def search(**kwargs):
    return (await CorpusRelationSource().search(query(**kwargs))).symptoms


async def test_the_corpus_declares_symptoms_and_the_source_finds_one_by_feature_and_label() -> None:
    [found] = await search()

    assert found.id == "sym-ea-doc-not-listed" and found.score == pytest.approx(0.75)
    assert [item.id for item in found.checkItems] == [
        "check-ea-draft-date",
        "check-ea-search-period",
        "check-ea-participation",
        "check-ea-submit-state",
        "check-ea-filter-condition",
    ]


async def test_the_employee_view_has_no_causes_labels_or_staff_only_items() -> None:
    [found] = await search()

    assert found.causes is None and found.accessScope == "EMPLOYEE"
    assert all(item.label is None and item.employeeDisclosable for item in found.checkItems)
    assert "check-ea-view-permission-record" not in {item.id for item in found.checkItems}
    assert all(q.text.strip() for item in found.checkItems for q in item.questions)


async def test_the_score_adds_half_for_the_feature_and_half_the_matched_label_ratio() -> None:
    feature_only = await search(keywords=("없는말",))
    label_only = await search(keywords=("문서", "목록"), features=())

    assert feature_only[0].score == pytest.approx(0.5)
    # 증상 이름에 "문서"는 있고 "목록"은 없다: 0.5 × (1/2)
    assert label_only[0].score == pytest.approx(0.25)
    assert not await search(keywords=("없는말",), features=())


async def test_symptoms_of_other_systems_are_not_returned() -> None:
    assert await search(system=LOGIN) == [] or all(
        s.systemId == LOGIN["id"] for s in await search(system=LOGIN)
    )


async def test_the_limit_and_the_order_follow_score_then_catalog_key() -> None:
    source = CorpusRelationSource()
    every_feature = {f["id"] for system in SYSTEMS for f in system["features"]}
    ids = frozenset(system["id"] for system in SYSTEMS)
    wide = RelationQuery(
        keywords=("로그인",),
        feature_ids=frozenset(every_feature),
        system_ids=ids,
        allowed_scopes=frozenset({"EMPLOYEE"}),
        limit=3,
    )

    found = (await source.search(wide)).symptoms

    assert len(found) == 3
    assert [s.score for s in found] == sorted((s.score for s in found), reverse=True)


def test_an_unknown_feature_code_in_the_corpus_is_an_error(tmp_path) -> None:
    runbooks = tmp_path / "runbooks"
    runbooks.mkdir()
    (runbooks / "x.md").write_text(
        "---\nreviewStatus: APPROVED\ndataSplit: RAG\nsystemCode: ELECTRONIC_APPROVAL\n"
        "symptoms:\n  - id: sym-x\n    label: 라벨\n    featureCode: NOPE\n    causes: []\n---\n",
        encoding="utf-8",
    )
    questions = tmp_path / "q.yml"
    questions.write_text("questions: []\n", encoding="utf-8")

    with pytest.raises(CorpusRelationError):
        CorpusRelationSource(tmp_path, questions)


def test_every_feature_name_maps_to_a_catalog_feature() -> None:
    names = {f["name"] for system in SYSTEMS for f in system["features"]}
    assert set(FEATURE_NAMES.values()) == names


# --- 관계 조회 기록 ---


def relation_result(state="FOUND") -> KnowledgeResult:
    neighbors = RelationNeighbors(
        state=state,
        symptoms=[
            RelationSymptom(
                id="sym-x",
                label="증상",
                checkItems=[
                    RelationCheck(
                        id="check-a", questions=[RelationQuestion(id="q-a-01", text="a?")]
                    ),
                    RelationCheck(
                        id="check-b", questions=[RelationQuestion(id="q-b-01", text="b?")]
                    ),
                ],
            )
        ]
        if state == "FOUND"
        else [],
    )
    return KnowledgeResult(
        invocationId="i",
        search=SearchResult(state="EMPTY", evidence=[], error=None),
        guide=None,
        problemRef="new:1",
        relations=neighbors,
    )


class Inner:
    role = AgentRole.KNOWLEDGE

    def __init__(self, result):
        self._result = result

    async def run(self, request, services):
        return self._result


async def test_the_recorder_keeps_the_relation_state_and_check_item_ids() -> None:
    log = CallLog()

    await RecordingAgent(Inner(relation_result()), log).run(None, None)

    [call] = log.role_calls
    assert call.relation_state == "FOUND" and call.relation_check_ids == ("check-a", "check-b")


def relation_trace(state="FOUND", ids=("check-a",)):
    request = request_with()
    call = RoleCall(
        AgentRole.KNOWLEDGE, problem_ref="new:1", relation_state=state, relation_check_ids=ids
    )
    return run_trace(turn(request, ai_result(request), calls=[call]))


def test_relation_records_survive_the_round_trip_and_old_traces_still_load() -> None:
    original = relation_trace()
    assert trace_from_dict(json.loads(json.dumps(trace_to_dict(original)))) == original

    data = trace_to_dict(original)
    for call in data["turns"][0]["role_calls"]:
        del call["relation_state"], call["relation_check_ids"]
    old = trace_from_dict(data).turns[0].role_calls[0]
    assert old.relation_state is None and old.relation_check_ids == ()


def test_the_report_lists_the_relation_lookups_of_a_turn() -> None:
    summary = _turn_summary(1, relation_trace("FOUND", ("check-a", "check-b")).turns[0])

    assert summary["relations"] == [
        {"problem": "new:1", "state": "FOUND", "checkItems": ["check-a", "check-b"]}
    ]
    assert "relations" not in _turn_summary(1, relation_trace(None, ()).turns[0])


def test_run_metadata_records_whether_the_relation_stand_in_was_used() -> None:
    assert run_metadata("m")["relationSource"] is None
    assert run_metadata("m", relations=True)["relationSource"] == CORPUS_RELATION_SOURCE_NAME
    assert "실조회 아님" in CORPUS_RELATION_SOURCE_NAME


def test_the_pilot_registry_gets_the_relation_source_only_when_asked(monkeypatch) -> None:
    monkeypatch.setenv("OPENAI_API_KEY", "sk-test")
    from evaluation.harness.scenario import parse_scenario
    from tests.unit.test_eval_user_sim import SCENARIO

    scenario = parse_scenario(SCENARIO)
    for flag in (False, True):
        _, agents_for, _ = _build_real(None, judgment=True, corpus=True, relations=flag)
        knowledge = agents_for(scenario)[AgentRole.KNOWLEDGE]
        assert (knowledge._relation_source is not None) is flag  # noqa: SLF001
