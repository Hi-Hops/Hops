"""의도별 양식과 질문 선택 규칙. 모델·DB 없이 순수 함수만 확인한다."""

import pytest

from hops_ai.agents.inquiry.forms import (
    FIELD_PROMPTS,
    MAX_FIELDS_PER_QUESTION,
    MAX_QUESTIONS_PER_PROBLEM,
    REQUIRED_FIELDS,
    SYSTEM,
    compose_question,
    missing_fields,
    plan_next,
)

SYSTEMS = ["전자결재", "공통 로그인", "파일 공유"]
ASKABLE = ["HOW_TO", "ACCESS_REQUEST", "OBSERVED_SYMPTOM", "FEATURE_REQUEST", "IMPROVEMENT_REQUEST"]


def everything(intent: str) -> dict[str, str]:
    return {name: f"{name} 값" for name in REQUIRED_FIELDS[intent]}


def test_every_required_field_has_a_prompt() -> None:
    required = {name for fields in REQUIRED_FIELDS.values() for name in fields}

    assert required <= set(FIELD_PROMPTS)


@pytest.mark.parametrize("intent", ASKABLE)
def test_complete_when_system_and_every_required_field_are_known(intent) -> None:
    plan = plan_next(intent, everything(intent), system_known=True)

    assert plan.action == "COMPLETE"
    assert plan.text is None and plan.fields == ()


@pytest.mark.parametrize("intent", ASKABLE)
def test_nothing_known_asks_system_first_then_required_fields_in_form_order(intent) -> None:
    plan = plan_next(intent, {}, system_known=False, system_names=SYSTEMS)

    assert plan.action == "ASK"
    expected = [SYSTEM, *REQUIRED_FIELDS[intent]][:MAX_FIELDS_PER_QUESTION]
    assert list(plan.fields) == expected
    assert "전자결재 / 공통 로그인 / 파일 공유" in plan.text


def test_known_fields_are_not_asked_again() -> None:
    known = {
        "taskOrScreen": "문서 조회 화면",
        "expectedBehavior": "문서 목록",
        "actualBehavior": None,
    }

    plan = plan_next("OBSERVED_SYMPTOM", known, system_known=True)

    assert plan.action == "ASK"
    assert plan.fields == ("actualBehavior", "occurredAt")


def test_blank_values_do_not_count_as_known() -> None:
    known = {"taskOrScreen": "   ", "currentWorkflow": ""}

    assert missing_fields("HOW_TO", known, system_known=True) == ["taskOrScreen", "currentWorkflow"]


def test_one_question_asks_at_most_three_fields() -> None:
    plan = plan_next("OBSERVED_SYMPTOM", {}, system_known=False, system_names=SYSTEMS)

    assert len(plan.fields) == MAX_FIELDS_PER_QUESTION == 3
    assert plan.text.count("\n") == 3  # 안내 한 줄 + 번호 붙은 세 항목


def test_single_missing_field_is_a_plain_sentence_not_a_list() -> None:
    plan = plan_next("ACCESS_REQUEST", {"requestedAccess": "파일 읽기"}, system_known=True)

    assert plan.fields == ("businessPurpose",)
    assert plan.text == FIELD_PROMPTS["businessPurpose"]


def test_system_without_names_still_asks() -> None:
    assert compose_question([SYSTEM]) == "어느 시스템에 대한 문의인가요?"


def test_question_text_is_deterministic() -> None:
    first = plan_next("OBSERVED_SYMPTOM", {}, system_known=False, system_names=SYSTEMS)
    second = plan_next("OBSERVED_SYMPTOM", {}, system_known=False, system_names=SYSTEMS)

    assert first == second


def test_fields_already_asked_are_not_repeated() -> None:
    plan = plan_next(
        "OBSERVED_SYMPTOM",
        {"taskOrScreen": "문서 조회"},
        system_known=True,
        asked={"expectedBehavior", "actualBehavior"},
    )

    assert plan.action == "ASK"
    assert plan.fields == ("occurredAt",)


def test_hands_off_when_everything_left_was_already_asked() -> None:
    plan = plan_next(
        "ACCESS_REQUEST",
        {"requestedAccess": "읽기"},
        system_known=True,
        asked={"businessPurpose"},
    )

    assert plan.action == "HANDOFF"
    assert plan.unknown == ("businessPurpose",)
    assert plan.text is None


def test_fields_the_user_does_not_know_are_not_asked_and_not_invented() -> None:
    plan = plan_next(
        "OBSERVED_SYMPTOM",
        {"taskOrScreen": "문서 조회", "expectedBehavior": "문서 표시", "actualBehavior": "0건"},
        system_known=True,
        unknown={"occurredAt"},
    )

    assert plan.action == "COMPLETE"
    assert plan.unknown == ("occurredAt",)


def test_unknown_system_is_asked_even_when_the_user_declined_other_fields() -> None:
    plan = plan_next(
        "HOW_TO", {}, system_known=False, system_names=SYSTEMS, unknown={"currentWorkflow"}
    )

    assert plan.action == "ASK"
    assert plan.fields == (SYSTEM, "taskOrScreen")


def test_user_who_does_not_know_the_system_is_not_asked_again_and_cannot_complete() -> None:
    plan = plan_next(
        "HOW_TO",
        {"taskOrScreen": "무언가", "currentWorkflow": "직접 처리"},
        system_known=False,
        unknown={SYSTEM},
    )

    # 임의 담당자에게 배정하지 않고 시스템 확인 대기로 넘기도록 시스템을 미확인으로 알린다
    assert plan.action == "HANDOFF"
    assert plan.unknown == (SYSTEM,)
    assert plan.text is None


def test_hands_off_at_the_question_limit_and_keeps_missing_as_unknown() -> None:
    plan = plan_next(
        "OBSERVED_SYMPTOM",
        {"taskOrScreen": "문서 조회"},
        system_known=True,
        question_count=MAX_QUESTIONS_PER_PROBLEM,
    )

    assert plan.action == "HANDOFF"
    assert plan.unknown == ("expectedBehavior", "actualBehavior", "occurredAt")


def test_still_asks_one_question_before_the_limit() -> None:
    plan = plan_next(
        "OBSERVED_SYMPTOM", {}, system_known=True, question_count=MAX_QUESTIONS_PER_PROBLEM - 1
    )

    assert plan.action == "ASK"


def test_out_of_scope_is_never_asked() -> None:
    plan = plan_next("OUT_OF_SCOPE", {}, system_known=False)

    assert plan.action == "OUT_OF_SCOPE"
    assert missing_fields("OUT_OF_SCOPE", {}, system_known=False) == []


def test_unknown_intent_asks_what_the_user_wants_to_do() -> None:
    plan = plan_next("UNKNOWN", {}, system_known=True)

    assert plan.action == "ASK"
    assert plan.fields == ("taskOrScreen",)


# --- 안내를 먼저 찾을 수 있을 때(Knowledge 연결): 사용법은 검색에 필요한 정보만 확보한다 ---


def test_guide_first_how_to_needs_only_system_and_task_to_search() -> None:
    known = {"taskOrScreen": "전자결재 문서 조회"}

    plan = plan_next("HOW_TO", known, system_known=True, guide_first=True)

    assert plan.action == "COMPLETE"


def test_guide_first_how_to_asks_the_task_but_never_the_current_workflow() -> None:
    plan = plan_next("HOW_TO", {}, system_known=True, guide_first=True)

    assert plan.action == "ASK"
    assert plan.fields == ("taskOrScreen",)


def test_guide_first_how_to_still_asks_the_system_first() -> None:
    plan = plan_next("HOW_TO", {}, system_known=False, system_names=SYSTEMS, guide_first=True)

    assert plan.fields == (SYSTEM, "taskOrScreen")


def test_without_guide_first_how_to_keeps_the_existing_form() -> None:
    known = {"taskOrScreen": "전자결재 문서 조회"}

    plan = plan_next("HOW_TO", known, system_known=True)

    assert plan.action == "ASK" and plan.fields == ("currentWorkflow",)


@pytest.mark.parametrize("intent", [*(i for i in ASKABLE if i != "HOW_TO"), "UNKNOWN"])
def test_guide_first_does_not_change_other_intents(intent) -> None:
    for known, system_known in (({}, False), (everything(intent), True)):
        assert plan_next(
            intent, known, system_known=system_known, system_names=SYSTEMS, guide_first=True
        ) == plan_next(intent, known, system_known=system_known, system_names=SYSTEMS)


# --- 가이드를 찾지 못한 뒤: 사용법은 담당자 전달에 필요한 항목을 확인한다 ---


def test_handoff_how_to_asks_the_desired_result_not_the_current_workflow() -> None:
    known = {"taskOrScreen": "전자결재 문서 조회"}

    plan = plan_next("HOW_TO", known, system_known=True, guide_first=True, handoff=True)

    assert plan.action == "ASK" and plan.fields == ("expectedBehavior",)
    assert "원하시는 결과" in plan.text
    # 장애용 문구("원래 어떻게 동작하길 기대하셨나요?")를 사용법에 쓰지 않는다.
    assert "기대하셨나요" not in plan.text


def test_handoff_how_to_is_complete_once_the_desired_result_is_known() -> None:
    known = {"taskOrScreen": "문서 조회", "expectedBehavior": "지난달 문서를 찾고 싶다"}

    plan = plan_next("HOW_TO", known, system_known=True, guide_first=True, handoff=True)

    assert plan.action == "COMPLETE"


def test_handoff_does_not_ask_what_the_user_said_they_do_not_know() -> None:
    known = {"taskOrScreen": "문서 조회"}

    plan = plan_next(
        "HOW_TO",
        known,
        system_known=True,
        guide_first=True,
        handoff=True,
        unknown={"expectedBehavior"},
    )

    assert plan.action == "COMPLETE" and plan.text is None


def test_handoff_respects_the_question_limit_and_keeps_missing_as_unknown() -> None:
    plan = plan_next(
        "HOW_TO",
        {"taskOrScreen": "문서 조회"},
        system_known=True,
        guide_first=True,
        handoff=True,
        question_count=MAX_QUESTIONS_PER_PROBLEM,
    )

    assert plan.action == "HANDOFF" and plan.unknown == ("expectedBehavior",)


@pytest.mark.parametrize("intent", [*(i for i in ASKABLE if i != "HOW_TO"), "UNKNOWN"])
def test_handoff_does_not_change_other_intents(intent) -> None:
    for known, system_known in (({}, False), (everything(intent), True)):
        assert plan_next(
            intent, known, system_known=system_known, system_names=SYSTEMS, handoff=True
        ) == plan_next(intent, known, system_known=system_known, system_names=SYSTEMS)
