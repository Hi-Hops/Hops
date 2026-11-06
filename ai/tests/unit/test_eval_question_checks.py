"""B7·B8 검사기와 질문 문장 출처 기록. 합성 기록과 Fake 모델만 쓴다."""

import json

from evaluation.harness.behavior import BEHAVIOR_CHECKS, check_b7, check_b8
from evaluation.harness.checks import CHECKS
from evaluation.harness.recorders import CallLog, RecordingAgent
from evaluation.harness.trace import AssessmentRecord, RoleCall
from evaluation.harness.trace_io import trace_from_dict, trace_to_dict
from evaluation.pilot import _count_questions, _turn_summary
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.roles import InquiryResult
from tests.eval_fixtures import (
    ai_result,
    evidence,
    problem_dict,
    question,
    request_with,
    review_ticket,
    run_trace,
    turn,
)

REF = "new:1"


def assessed(verdict: str, *missing: str) -> RoleCall:
    return RoleCall(
        AgentRole.KNOWLEDGE,
        problem_ref=REF,
        search_state="FOUND",
        evidence=(evidence(),),
        assessment=AssessmentRecord(
            problem_ref=REF,
            verdict=verdict,
            missing_information=tuple(("FIELD", name) for name in missing),
        ),
    )


def trace(calls, *, guide=None, tickets=(), questions=()):
    request = request_with()
    ptype = "REVIEW_REQUIRED" if tickets else "KNOWLEDGE_QA"
    # 결과 계약: 답변 본문은 질문이나 안내 문장과 일치해야 한다
    answer = (
        questions[0]["text"] if questions else guide["messageText"] if guide else "안내드립니다."
    )
    result = ai_result(
        request,
        answer=answer,
        problems=[problem_dict(REF, ptype=ptype)],
        guide=guide,
        tickets=tickets,
        questions=questions,
        evidence_items=[evidence()] if guide else [],
    )
    return run_trace(turn(request, result, calls=calls))


def guide(ref=REF) -> dict:
    return {
        "problemRef": ref,
        "messageText": "가이드를 확인하세요.",
        "evidenceIds": [evidence().id],
    }


def test_all_behavior_checks_are_registered_apart_from_the_safety_properties() -> None:
    assert {"B7", "B8", "B9"} <= set(BEHAVIOR_CHECKS) and not set(BEHAVIOR_CHECKS) & set(CHECKS)


# --- B7: 판정과 종결의 일관성 ---


def test_b7_flags_a_sufficient_verdict_that_ended_without_a_guide_in_a_ticket() -> None:
    found = check_b7(trace([assessed("SUFFICIENT")], tickets=[review_ticket(REF)]))

    assert [item.property_id for item in found] == ["B7"] and "충분" in found[0].message


def test_b7_flags_a_guide_given_on_a_verdict_that_was_not_sufficient() -> None:
    found = check_b7(trace([assessed("PARTIAL")], guide=guide()))

    assert [item.property_id for item in found] == ["B7"] and "PARTIAL" in found[0].message


def test_b7_accepts_the_consistent_outcomes() -> None:
    assert check_b7(trace([assessed("SUFFICIENT")], guide=guide())) == []
    assert check_b7(trace([assessed("PARTIAL")], tickets=[review_ticket(REF)])) == []
    asking = [question(REF, "더 알려 주세요", "occurredAt")]
    assert check_b7(trace([assessed("SUFFICIENT", "occurredAt")], questions=asking)) == []


def test_b7_uses_the_last_verdict_of_a_problem_that_was_searched_again() -> None:
    calls = [assessed("PARTIAL"), assessed("SUFFICIENT")]

    assert check_b7(trace(calls, guide=guide())) == []


# --- B8: 질문이 부족한 정보를 겨냥 ---


def test_b8_flags_a_question_that_asks_none_of_the_missing_information() -> None:
    asking = [question(REF, "언제부터인가요?", "occurredAt")]

    found = check_b8(trace([assessed("PARTIAL", "actualBehavior")], questions=asking))

    assert [item.property_id for item in found] == ["B8"]


def test_b8_accepts_a_question_that_includes_a_missing_field_among_others() -> None:
    asking = [question(REF, "여러 가지", "taskOrScreen", "actualBehavior")]

    assert check_b8(trace([assessed("PARTIAL", "actualBehavior")], questions=asking)) == []


def test_b8_has_nothing_to_check_without_missing_information_or_a_question() -> None:
    asking = [question(REF, "언제부터인가요?", "occurredAt")]

    assert check_b8(trace([assessed("PARTIAL")], questions=asking)) == []
    assert check_b8(trace([assessed("PARTIAL", "actualBehavior")])) == []


# --- 질문 문장의 출처 기록 ---


class Inner:
    role = AgentRole.INQUIRY

    def __init__(self, result: InquiryResult) -> None:
        self._result = result

    async def run(self, request, services):
        return self._result


def inquiry_result(**changes) -> InquiryResult:
    return InquiryResult(invocationId="i", problems=[], questions=[], **changes)


async def test_the_recorder_keeps_the_question_source_and_fallback_code() -> None:
    log = CallLog()
    result = inquiry_result(questionSource="FIXED_FALLBACK", questionFallback="INVALID:TOO_LONG")

    await RecordingAgent(Inner(result), log).run(None, None)

    [call] = log.role_calls
    assert (call.question_source, call.question_fallback) == ("FIXED_FALLBACK", "INVALID:TOO_LONG")


def source_trace(source: str, fallback: str | None = None):
    request = request_with()
    call = RoleCall(AgentRole.INQUIRY, question_source=source, question_fallback=fallback)
    return run_trace(turn(request, ai_result(request), calls=[call]))


def test_the_source_survives_the_trace_round_trip_and_old_traces_still_load() -> None:
    original = source_trace("MODEL")

    restored = trace_from_dict(json.loads(json.dumps(trace_to_dict(original))))
    assert restored == original

    data = trace_to_dict(original)
    for call in data["turns"][0]["role_calls"]:
        del call["question_source"], call["question_fallback"]
    assert trace_from_dict(data).turns[0].role_calls[0].question_source is None


def test_the_report_lists_the_question_sources_and_totals_count_model_and_fallback() -> None:
    model = _turn_summary(1, source_trace("MODEL").turns[0])
    fallback = _turn_summary(1, source_trace("FIXED_FALLBACK", "BUDGET").turns[0])

    assert model["questionSources"] == [{"source": "MODEL", "fallback": None}]
    assert fallback["questionSources"] == [{"source": "FIXED_FALLBACK", "fallback": "BUDGET"}]
    reports = [{"turns": [model, fallback]}]
    assert _count_questions(reports, "MODEL") == 1
    assert _count_questions(reports, "FIXED_FALLBACK") == 1


def test_a_turn_without_a_question_action_has_no_source_entry() -> None:
    request = request_with()

    assert "questionSources" not in _turn_summary(1, turn(request, ai_result(request)))
