"""멀티턴: Spring이 저장한 질문 이력(`askedFields`)으로 같은 항목을 다시 묻지 않는다(P7).

모델은 Fake이며 실제 모델 품질을 검증하지 않는다.
이력이 없으면(예전 입력) 이전 동작 그대로인지도 함께 본다.
"""

import pytest
from pydantic import ValidationError

from hops_ai.agents.inquiry.asking import question_plan
from hops_ai.agents.inquiry.extraction import QuotedValue
from hops_ai.agents.inquiry.readiness import (
    get_handoff_readiness,
    inquiry_has_nothing_left_to_do,
)
from hops_ai.contracts.roles import InquiryResult
from hops_ai.contracts.wire import AiRunRequest
from tests.supervisor_fixtures import MESSAGE, PROBLEM, make_problem, make_request, running_state
from tests.unit.test_handoff_readiness import details
from tests.unit.test_inquiry_agent import extracted, run_agent
from tests.unit.test_knowledge_empty_handoff import empty_search, how_to_problem

ALL_MISSING = ["taskOrScreen", "expectedBehavior", "occurredAt"]
# 원문("전자결재 첨부파일이 열리지 않아요")에서 실제 동작만 확인되고 나머지 필수 항목은 비어 있다.
SYMPTOM = extracted(actualBehavior=QuotedValue(messageId=MESSAGE, quote="열리지 않아요"))


def request_with(asked: list[str] | None, *, question_count: int = 1, **problem) -> AiRunRequest:
    """질문을 이미 한 기존 문제가 있는 후속 실행 입력. asked가 None이면 askedFields 키가 없다."""
    fields = make_request(question_count=question_count).model_dump(mode="json")
    if asked is None:
        fields["problems"][0].pop("askedFields", None)
    else:
        fields["problems"][0]["askedFields"] = asked
    fields["problems"][0].update(problem)
    return AiRunRequest.model_validate(fields)


# -- 계약 -------------------------------------------------------------------------------------


def test_asked_fields_are_optional_for_older_producers() -> None:
    fields = make_request().model_dump(mode="json")
    fields["problems"][0].pop("askedFields")

    request = AiRunRequest.model_validate(fields)

    assert request.problems[0].askedFields == []


def test_asked_fields_are_carried_as_given() -> None:
    assert request_with(["taskOrScreen"]).problems[0].askedFields == ["taskOrScreen"]


def test_unknown_keys_on_a_problem_are_still_rejected() -> None:
    fields = make_request().model_dump(mode="json")
    fields["problems"][0]["askedField"] = ["taskOrScreen"]

    with pytest.raises(ValidationError):
        AiRunRequest.model_validate(fields)


# -- 분석 경로(기본) ----------------------------------------------------------------------------


async def test_without_history_the_same_fields_are_asked_again() -> None:
    """이력이 없던 예전 동작. 답을 못 얻은 항목이 그대로 다시 질문된다."""
    result = await run_agent(request_with(None), SYMPTOM)

    [question] = result.questions
    assert question.requestedFields == ALL_MISSING


async def test_fields_already_asked_are_not_asked_again() -> None:
    result = await run_agent(request_with(["taskOrScreen"]), SYMPTOM)

    [question] = result.questions
    assert question.requestedFields == ["expectedBehavior", "occurredAt"]


async def test_when_everything_missing_was_asked_the_problem_is_handed_off_without_a_question() -> (
    None
):
    result = await run_agent(request_with(ALL_MISSING), SYMPTOM)

    [problem] = result.problems
    assert result.questions == []
    assert problem.unknowns == ["하려던 업무·화면", "기대한 동작", "발생 시점"]


async def test_a_field_the_user_answered_is_known_and_never_asked() -> None:
    answered = SYMPTOM.model_copy(
        update={"taskOrScreen": QuotedValue(messageId=MESSAGE, quote="첨부파일")}
    )

    result = await run_agent(request_with(["taskOrScreen"]), answered)

    [question] = result.questions
    assert "taskOrScreen" not in question.requestedFields


# -- 문제별 행동 경로(판정 모드) ------------------------------------------------------------------


def test_question_plan_skips_fields_already_asked() -> None:
    request = request_with(["taskOrScreen", "expectedBehavior"])
    state = {
        **running_state(request),
        "problems": {PROBLEM: make_problem(PROBLEM)},
    }

    plan = question_plan(state, PROBLEM, searched=False)

    assert plan.action == "ASK"
    assert "taskOrScreen" not in plan.fields and "expectedBehavior" not in plan.fields


def test_question_plan_hands_off_when_all_remaining_fields_were_asked() -> None:
    request = request_with(ALL_MISSING)
    state = {
        **running_state(request),
        "problems": {PROBLEM: make_problem(PROBLEM)},
    }

    assert question_plan(state, PROBLEM, searched=False).action == "HANDOFF"


# -- 준비 상태 ----------------------------------------------------------------------------------


def how_to_state(asked: list[str]):
    request = request_with(asked, intent="HOW_TO", processingType="KNOWLEDGE_QA", state="ANALYZING")
    problem = how_to_problem(PROBLEM, details=details(taskOrScreen="결재선 변경"))
    search = empty_search(problem_ref=PROBLEM)
    return {
        **running_state(request),
        "problems": {PROBLEM: problem},
        "roleResults": {search.invocationId: search},
    }


def test_handoff_readiness_has_no_questions_left_when_the_missing_item_was_asked() -> None:
    [item] = get_handoff_readiness(how_to_state(["expectedBehavior"]))

    assert item["missingForHandoff"] == ["expectedBehavior"]
    assert item["remainingQuestions"] == 0


def test_handoff_readiness_keeps_the_remaining_questions_without_history() -> None:
    [item] = get_handoff_readiness(how_to_state([]))

    assert item["remainingQuestions"] == 4


def inquiry_done_state(asked: list[str]):
    request = request_with(asked)
    problem = make_problem(PROBLEM).model_copy(
        update={"details": details(actualBehavior="열리지 않아요")}
    )
    return {
        **running_state(request),
        "problems": {PROBLEM: problem},
        "roleResults": {"inv-i": InquiryResult(invocationId="inv-i", problems=[], questions=[])},
    }


def test_inquiry_has_more_to_ask_while_a_missing_field_was_not_asked() -> None:
    assert inquiry_has_nothing_left_to_do(inquiry_done_state(["taskOrScreen"])) is False


def test_inquiry_has_nothing_left_when_every_missing_field_was_already_asked() -> None:
    assert inquiry_has_nothing_left_to_do(inquiry_done_state(ALL_MISSING)) is True
