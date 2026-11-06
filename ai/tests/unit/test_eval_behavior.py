"""행동 성질 B9 검사기. 합성 기록으로 위반과 정상 기록을 가른다(모델·검색은 쓰지 않는다)."""

from evaluation.harness.behavior import BEHAVIOR_CHECKS, check_b9, check_behavior
from evaluation.harness.checks import CHECKS
from evaluation.harness.trace import AssessmentRecord, RoleCall
from hops_ai.contracts.domain import AgentRole
from tests.eval_fixtures import (
    ai_result,
    evidence,
    knowledge_call,
    problem_dict,
    request_with,
    review_ticket,
    run_trace,
    turn,
)

REF = "new:1"
SOURCE_ALIAS = "m1"


def trace(intent: str, *, calls=(), tickets=(), guide=None, status="SUCCEEDED"):
    request = request_with()
    # 티켓과 문제의 처리 유형이 같아야 결과 계약을 통과한다
    ptype = "REVIEW_REQUIRED" if tickets else "KNOWLEDGE_QA"
    problem = {**problem_dict(REF, ptype=ptype), "intent": intent}
    result = ai_result(request, problems=[problem], tickets=tickets, guide=guide, status=status)
    return run_trace(turn(request, result, calls=calls))


def assessed(ref: str = REF, verdict: str = "PARTIAL") -> RoleCall:
    return RoleCall(
        AgentRole.KNOWLEDGE,
        problem_ref=ref,
        search_state="FOUND",
        evidence=(evidence(),),
        assessment=AssessmentRecord(problem_ref=ref, verdict=verdict),
    )


def test_behavior_checks_are_not_part_of_the_safety_properties() -> None:
    assert "B9" in BEHAVIOR_CHECKS and "B9" not in CHECKS


def test_a_feature_request_that_was_searched_is_flagged() -> None:
    found = check_b9(trace("FEATURE_REQUEST", calls=[knowledge_call(REF, "EMPTY")]))

    assert [item.property_id for item in found] == ["B9"]
    assert "FEATURE_REQUEST" in found[0].message


def test_improvement_and_feature_requests_without_search_pass() -> None:
    for intent in ("FEATURE_REQUEST", "IMPROVEMENT_REQUEST"):
        assert check_b9(trace(intent, tickets=[review_ticket(REF)])) == []


def test_a_search_for_another_problem_does_not_count_against_a_feature_request() -> None:
    assert check_b9(trace("FEATURE_REQUEST", calls=[knowledge_call("other", "FOUND")])) == []


def test_a_symptom_that_ended_in_a_ticket_without_search_is_flagged() -> None:
    found = check_b9(trace("OBSERVED_SYMPTOM", tickets=[review_ticket(REF)]))

    assert [item.property_id for item in found] == ["B9"]
    assert "검색하지 않은" in found[0].message


def test_a_symptom_with_found_evidence_but_no_assessment_is_flagged() -> None:
    calls = [knowledge_call(REF, "FOUND", [evidence()])]

    found = check_b9(trace("OBSERVED_SYMPTOM", calls=calls, tickets=[review_ticket(REF)]))

    assert [item.property_id for item in found] == ["B9"]
    assert "판정 없이" in found[0].message


def test_a_symptom_that_was_searched_and_assessed_passes() -> None:
    assert (
        check_b9(trace("OBSERVED_SYMPTOM", calls=[assessed()], tickets=[review_ticket(REF)])) == []
    )


def test_an_empty_search_needs_no_assessment() -> None:
    calls = [knowledge_call(REF, "EMPTY")]

    assert check_b9(trace("OBSERVED_SYMPTOM", calls=calls, tickets=[review_ticket(REF)])) == []


def test_a_symptom_that_ended_with_a_question_is_not_judged() -> None:
    assert check_b9(trace("OBSERVED_SYMPTOM")) == []


def test_failed_runs_are_not_judged() -> None:
    assert check_behavior(trace("FEATURE_REQUEST", status="FAILED")) == []
