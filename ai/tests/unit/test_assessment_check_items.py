"""판정이 관계 확인 항목을 부족한 정보로 지목하는 경로. 모델과 자료는 Fake다.

판정이 실제로 좋은 항목을 고르는지는 검증하지 않는다. 입력에 확인 항목이 실리는지, 허용된 ID만
받아들이는지, 허용되지 않은 값이 평가 없음이나 병합 거부로 막히는지를 본다.
"""

import json

import pytest

from hops_ai.agents.knowledge import SearchKnowledgeAgent
from hops_ai.agents.knowledge.assessment import (
    MAX_CHECK_ITEMS,
    check_item_payload,
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
from hops_ai.harness.errors import OutputRejected
from hops_ai.rules.assessment import check_item_ids
from hops_ai.supervisor.execution import execute_role
from hops_ai.supervisor.merge import merge_knowledge_result
from tests.supervisor_fixtures import PROBLEM, reserve
from tests.unit.test_knowledge_agent import RecordingSource
from tests.unit.test_knowledge_assessment import DEFAULT_HITS, GUIDE, draft, services_with
from tests.unit.test_knowledge_targets import symptom_request
from tests.unit.test_relation_lookup import FakeRelations, analysed, check, symptom


def neighbors(*ids: str) -> RelationNeighbors:
    checks = [
        RelationCheck(
            id=key, questions=[RelationQuestion(id=f"q-{key}-01", text=f"{key}를 알려 주세요")]
        )
        for key in ids
    ]
    return RelationNeighbors(
        state="FOUND" if ids else "EMPTY",
        symptoms=[RelationSymptom(id="sym", label="증상", checkItems=checks)] if ids else [],
    )


def missing(*refs: str, kind="CHECK_ITEM"):
    return [{"kind": kind, "ref": ref} for ref in refs]


# --- 모델 입력 ---


def test_the_payload_lists_each_check_item_once_with_its_approved_question() -> None:
    two = RelationNeighbors(
        state="FOUND",
        symptoms=[
            RelationSymptom(
                id="a",
                label="가",
                checkItems=[
                    RelationCheck(id="c1", questions=[RelationQuestion(id="q1", text="하나?")]),
                    RelationCheck(id="c2", questions=[RelationQuestion(id="q2", text="둘?")]),
                ],
            ),
            RelationSymptom(
                id="b",
                label="나",
                checkItems=[
                    RelationCheck(id="c1", questions=[RelationQuestion(id="q1", text="하나?")])
                ],
            ),
        ],
    )

    assert check_item_payload(two) == [
        {"id": "c1", "ask": "하나?"},
        {"id": "c2", "ask": "둘?"},
    ]
    assert check_item_payload(None) == [] and check_item_payload(neighbors()) == []


def test_the_payload_caps_the_number_of_check_items() -> None:
    many = neighbors(*[f"c{number}" for number in range(MAX_CHECK_ITEMS + 5)])

    assert len(check_item_payload(many)) == MAX_CHECK_ITEMS


def test_allowed_ids_are_exactly_the_looked_up_check_items() -> None:
    assert check_item_ids(neighbors("a", "b")) == {"a", "b"}
    assert check_item_ids(None) == frozenset() and check_item_ids(neighbors()) == frozenset()


# --- 에이전트: 허용된 ID만 받는다 ---


async def assess(outcome, relations):
    request = symptom_request()
    services, llm = services_with(request, outcome)
    agent = SearchKnowledgeAgent(
        RecordingSource(DEFAULT_HITS), assess_evidence=True, relation_source=relations
    )
    state = await execute_role(
        analysed(request),
        AgentRole.KNOWLEDGE,
        services,
        {AgentRole.KNOWLEDGE: agent},
        targets=(PROBLEM,),
    )
    return state, llm


def partial(*refs: str, kind="CHECK_ITEM"):
    return draft(
        verdict="PARTIAL",
        supportingEvidenceIds=[GUIDE.id],
        missingInformation=missing(*refs, kind=kind),
    )


async def test_the_relations_are_looked_up_before_the_assessment_and_reach_its_input() -> None:
    relations = FakeRelations(symptom(checks=[check("check-a"), check("check-b")]))

    _, llm = await assess(partial("check-a"), relations)

    [request] = llm.requests
    payload = json.loads(request.messages[1].content)
    assert [item["id"] for item in payload["checkItems"]] == ["check-a", "check-b"]
    assert payload["checkItems"][0]["ask"] == "조회 기간을 어떻게 설정하셨나요?"
    assert request.prompt_version == "knowledge.assessment.v3"


async def test_a_check_item_from_the_lookup_is_kept_in_the_assessment() -> None:
    relations = FakeRelations(symptom(checks=[check("check-a"), check("check-b")]))

    state, _ = await assess(partial("check-b", "check-a"), relations)

    kept = state["assessments"][PROBLEM]
    assert [(m.kind, m.ref) for m in kept.missingInformation] == [
        ("CHECK_ITEM", "check-b"),
        ("CHECK_ITEM", "check-a"),
    ]
    assert kept.nextChecks == []


async def test_a_check_item_that_was_not_looked_up_voids_the_assessment() -> None:
    relations = FakeRelations(symptom(checks=[check("check-a")]))

    state, llm = await assess(partial("check-made-up"), relations)

    assert len(llm.requests) == 1
    assert PROBLEM not in state["assessments"], "허용 밖 ID는 평가 없음으로 처리한다"
    assert state["guide"] is None or True  # 검색 결과와 안내는 판정과 무관하다


async def test_without_relations_a_check_item_is_still_refused() -> None:
    state, llm = await assess(partial("check-a"), None)

    assert PROBLEM not in state["assessments"]
    assert json.loads(llm.requests[0].messages[1].content)["checkItems"] == []


async def test_an_employee_check_item_the_lookup_dropped_cannot_be_named() -> None:
    relations = FakeRelations(
        symptom(checks=[check("check-ok"), check("check-staff", disclosable=False)])
    )

    state, _ = await assess(partial("check-staff"), relations)

    assert PROBLEM not in state["assessments"]


async def test_form_fields_are_still_accepted_alongside_check_items() -> None:
    relations = FakeRelations(symptom(checks=[check("check-a")]))
    both = draft(
        verdict="PARTIAL",
        supportingEvidenceIds=[GUIDE.id],
        missingInformation=[
            {"kind": "FIELD", "ref": "occurredAt"},
            {"kind": "CHECK_ITEM", "ref": "check-a"},
        ],
    )

    state, _ = await assess(both, relations)

    assert len(state["assessments"][PROBLEM].missingInformation) == 2


async def test_next_checks_follow_the_same_allowed_items() -> None:
    relations = FakeRelations(symptom(checks=[check("check-a")]))

    allowed, _ = await assess(
        draft(
            verdict="PARTIAL",
            supportingEvidenceIds=[GUIDE.id],
            nextChecks=[{"checkItemId": "check-a"}],
        ),
        relations,
    )
    refused, _ = await assess(
        draft(
            verdict="PARTIAL",
            supportingEvidenceIds=[GUIDE.id],
            nextChecks=[{"checkItemId": "check-made-up"}],
        ),
        relations,
    )

    assert allowed["assessments"][PROBLEM].nextChecks[0].checkItemId == "check-a"
    assert PROBLEM not in refused["assessments"]


def test_the_prompt_keeps_next_checks_empty_for_the_employee_profile() -> None:
    from hops_ai.agents.knowledge.assessment import SYSTEM_PROMPT

    assert "nextChecks는 항상 빈 목록" in SYSTEM_PROMPT


# --- 병합: 같은 규칙으로 다시 검사 ---


def merge_with(assessment_refs, relations):
    from hops_ai.contracts.roles import Assessment, MissingInformation

    request = symptom_request()
    state = reserve(analysed(request), AgentRole.KNOWLEDGE, "inv-1")
    result = KnowledgeResult(
        invocationId="inv-1",
        search=SearchResult(state="FOUND", evidence=[GUIDE], error=None),
        guide=None,
        problemRef=PROBLEM,
        relations=relations,
        assessment=Assessment(
            problemRef=PROBLEM,
            verdict="PARTIAL",
            supportingEvidenceIds=[GUIDE.id],
            missingInformation=[
                MissingInformation(kind="CHECK_ITEM", ref=r) for r in assessment_refs
            ],
            candidateCauses=[],
            nextChecks=[],
            rationale="내부용",
        ),
    )
    from tests.supervisor_fixtures import authorize

    return merge_knowledge_result(
        state, result, authorized_refs=[authorize(GUIDE)], verified_evidence=[GUIDE]
    )


def test_the_merge_accepts_check_items_the_same_result_looked_up() -> None:
    merged = merge_with(["check-a"], neighbors("check-a"))

    assert merged["assessments"][PROBLEM].missingInformation[0].ref == "check-a"
    assert merged["relations"][PROBLEM].state == "FOUND"


@pytest.mark.parametrize("relations", [None, neighbors(), neighbors("check-other")])
def test_the_merge_rejects_a_check_item_the_result_did_not_look_up(relations) -> None:
    with pytest.raises(OutputRejected):
        merge_with(["check-a"], relations)
