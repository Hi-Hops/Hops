"""판정 기록과 P10·P11 검사기. 모델·검색은 Fake이며 판정이 실제로 맞는지는 검증하지 않는다.

검사기는 저장된 기록만 보고 판정하므로, 에이전트와 병합의 검증이 빠져도 위반이 드러나는지
합성 기록으로 확인한다.
"""

import json

import pytest

from evaluation.harness.checks import check_p10, check_p11, check_trace
from evaluation.harness.recorders import CallLog, RecordingAgent, RecordingLLM
from evaluation.harness.trace import AssessmentRecord, RoleCall
from evaluation.harness.trace_io import trace_from_dict, trace_to_dict
from evaluation.pilot import _turn_summary
from hops_ai.agents.knowledge import SearchKnowledgeAgent
from hops_ai.contracts.domain import AgentRole
from hops_ai.supervisor.execution import RoleRequest
from tests.eval_fixtures import ai_result, knowledge_call, request_with, run_trace, turn
from tests.supervisor_fixtures import PROBLEM, make_evidence, reserve, running_state, uid
from tests.unit.test_knowledge_agent import RecordingSource, how_to_request
from tests.unit.test_knowledge_assessment import (
    DEFAULT_HITS,
    GUIDE,
    STAFF,
    draft,
    services_with,
)

OTHER = uid(900)


def record(verdict="SUFFICIENT", cited=(GUIDE.id,), missing=(), causes=()) -> AssessmentRecord:
    return AssessmentRecord(
        problem_ref=PROBLEM,
        verdict=verdict,
        supporting_evidence_ids=tuple(cited),
        missing_information=tuple(missing),
        candidate_causes=tuple(causes),
        rationale="내부용",
    )


def call_with(item: AssessmentRecord | None, *evidence) -> RoleCall:
    return RoleCall(
        AgentRole.KNOWLEDGE,
        problem_ref=PROBLEM,
        search_state="FOUND",
        evidence=tuple(evidence or (GUIDE,)),
        assessment=item,
    )


def trace_of(*calls: RoleCall):
    request = request_with((("m1", "전자결재에서 문서가 안 보여요"),))
    return run_trace(turn(request, ai_result(request), calls=calls))


def ids(violations) -> list[str]:
    return [violation.property_id for violation in violations]


# --- P10 ---


def test_p10_accepts_a_sufficient_assessment_backed_by_employee_evidence() -> None:
    assert check_p10(trace_of(call_with(record()))) == []


def test_p10_flags_sufficient_that_rests_only_on_staff_evidence() -> None:
    staff_only = call_with(record(cited=(STAFF.id,)), GUIDE, STAFF)

    found = check_p10(trace_of(staff_only))

    assert ids(found) == ["P10"] and "직원 공개 근거" in found[0].message


def test_p10_flags_sufficient_without_any_cited_evidence() -> None:
    assert ids(check_p10(trace_of(call_with(record(cited=()))))) == ["P10"]


def test_p10_flags_evidence_ids_that_are_not_in_that_calls_results() -> None:
    for verdict in ("SUFFICIENT", "PARTIAL"):
        found = check_p10(trace_of(call_with(record(verdict, cited=(GUIDE.id, OTHER)))))

        assert ids(found) == ["P10"] and "검색 결과에 없다" in found[0].message


def test_p10_allows_partial_to_cite_staff_evidence_and_ignores_calls_without_assessment() -> None:
    partial = call_with(record("PARTIAL", cited=(STAFF.id,)), GUIDE, STAFF)

    assert check_p10(trace_of(partial, call_with(None), knowledge_call(PROBLEM, "EMPTY"))) == []


# --- P11 ---


def test_p11_accepts_form_field_names() -> None:
    item = record("PARTIAL", missing=(("FIELD", "taskOrScreen"), ("FIELD", "occurredAt")))

    assert check_p11(trace_of(call_with(item))) == []


@pytest.mark.parametrize(
    "missing",
    [
        (("FIELD", "어느 화면에서 그러셨나요"),),  # 자유 문장
        (("FIELD", "system"),),  # 양식 필드가 아니다
        (("CHECK_ITEM", "item-1"),),  # 관계를 조회하지 않은 호출에는 허용된 항목이 없다
        (("OTHER", "taskOrScreen"),),
    ],
)
def test_p11_flags_missing_information_outside_the_closed_vocabulary(missing) -> None:
    found = check_p11(trace_of(call_with(record("PARTIAL", missing=missing))))

    assert ids(found) == ["P11"]


def call_with_relations(item, *check_ids: str) -> RoleCall:
    return RoleCall(
        AgentRole.KNOWLEDGE,
        problem_ref=PROBLEM,
        search_state="FOUND",
        evidence=(GUIDE,),
        assessment=item,
        relation_state="FOUND",
        relation_check_ids=tuple(check_ids),
    )


def test_p11_accepts_check_items_the_same_call_looked_up() -> None:
    item = record("PARTIAL", missing=(("CHECK_ITEM", "check-a"), ("FIELD", "occurredAt")))

    assert check_p11(trace_of(call_with_relations(item, "check-a", "check-b"))) == []


def test_p11_flags_a_check_item_the_call_did_not_look_up() -> None:
    item = record("PARTIAL", missing=(("CHECK_ITEM", "check-z"),))

    found = check_p11(trace_of(call_with_relations(item, "check-a")))

    assert ids(found) == ["P11"] and "check-z" in found[0].message


def test_p11_does_not_accept_a_field_name_as_a_check_item_or_the_reverse() -> None:
    as_item = record("PARTIAL", missing=(("CHECK_ITEM", "occurredAt"),))
    as_field = record("PARTIAL", missing=(("FIELD", "check-a"),))

    assert ids(check_p11(trace_of(call_with_relations(as_item, "check-a")))) == ["P11"]
    assert ids(check_p11(trace_of(call_with_relations(as_field, "check-a")))) == ["P11"]


def test_both_checkers_run_by_default_and_by_name() -> None:
    bad = call_with(record(cited=(OTHER,), missing=(("FIELD", "자유"),)))
    trace = trace_of(bad)

    assert {"P10", "P11"} <= set(ids(check_trace(trace)))
    assert ids(check_trace(trace, ("P11",))) == ["P11"]


# --- 기록 ---


async def recorded_call(*outcomes, hits=DEFAULT_HITS, assess=True) -> tuple[RoleCall, CallLog]:
    request = how_to_request()
    log = CallLog()
    services, _ = services_with(request, *outcomes)
    services = type(services)(
        runtime=services.runtime,
        llm=RecordingLLM(services.llm, log),
        tools=services.tools,
    )
    state = reserve(running_state(request), AgentRole.KNOWLEDGE, "inv-1")
    agent = RecordingAgent(SearchKnowledgeAgent(RecordingSource(hits), assess_evidence=assess), log)
    await agent.run(RoleRequest("inv-1", state), services)
    [call] = log.role_calls
    return call, log


async def test_recorder_keeps_the_assessment_of_a_found_search() -> None:
    call, _ = await recorded_call(
        draft(
            verdict="PARTIAL",
            missingInformation=[{"kind": "FIELD", "ref": "taskOrScreen"}],
        )
    )

    item = call.assessment
    assert item is not None
    assert (item.problem_ref, item.verdict) == (PROBLEM, "PARTIAL")
    assert item.supporting_evidence_ids == (GUIDE.id,)
    assert item.missing_information == (("FIELD", "taskOrScreen"),)
    assert call.search_state == "FOUND"


async def test_recorder_keeps_the_code_made_no_match_for_an_empty_search() -> None:
    call, _ = await recorded_call(hits=())

    assert call.search_state == "EMPTY"
    assert call.assessment is not None and call.assessment.verdict == "NO_MATCH"


async def test_recorder_leaves_the_assessment_empty_when_it_is_off() -> None:
    call, _ = await recorded_call(assess=False)

    assert call.search_state == "FOUND" and call.assessment is None


async def test_what_the_recorder_saves_passes_the_checkers() -> None:
    call, _ = await recorded_call(draft())

    assert check_p10(trace_of(call)) == [] and check_p11(trace_of(call)) == []


# --- 저장·복원과 보고서 ---


def test_assessment_survives_a_trace_round_trip() -> None:
    item = record(
        "PARTIAL",
        missing=(("FIELD", "taskOrScreen"),),
        causes=(("조회 기간이 좁다", False),),
    )
    trace = trace_of(call_with(item))

    restored = trace_from_dict(json.loads(json.dumps(trace_to_dict(trace), ensure_ascii=False)))

    assert restored == trace


def test_a_trace_saved_before_assessments_were_recorded_still_loads() -> None:
    data = trace_to_dict(trace_of(call_with(record())))
    for item in data["turns"]:
        for call in item["role_calls"]:
            del call["assessment"]

    restored = trace_from_dict(data)

    assert restored.turns[0].role_calls[0].assessment is None


def test_pilot_summary_lists_assessments_and_counts_found_searches_without_one() -> None:
    request = request_with((("m1", "전자결재에서 문서가 안 보여요"),))
    other = make_evidence(id=uid(910), documentId=uid(911))
    calls = [
        call_with(record("PARTIAL", missing=(("FIELD", "taskOrScreen"),))),
        RoleCall(
            AgentRole.KNOWLEDGE,
            problem_ref="new:2",
            search_state="FOUND",
            evidence=(other,),
        ),
        knowledge_call("new:3", "EMPTY"),
    ]

    summary = _turn_summary(1, turn(request, None, calls=calls))["assessments"]

    assert summary["items"] == [
        {
            "problemRef": PROBLEM,
            "verdict": "PARTIAL",
            "supportingEvidence": 1,
            "missingInformation": ["taskOrScreen"],
            "candidateCauses": 0,
            "rationale": "내부용",
        }
    ]
    assert summary["foundWithoutAssessment"] == 1


def test_pilot_summary_has_no_assessment_section_without_a_search() -> None:
    request = request_with((("m1", "전자결재에서 문서가 안 보여요"),))

    assert "assessments" not in _turn_summary(1, turn(request, None))
