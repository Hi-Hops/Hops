"""규칙 사용자 모사와 시나리오 형식 검사. 모델을 부르지 않는다."""

import json

import pytest

from evaluation.harness.scenario import ScenarioFormatError, load_scenario, parse_scenario
from evaluation.harness.user_sim import reply_to
from hops_ai.contracts.wire import QuestionProposal

SCENARIO = {
    "scenarioId": "S-ea-search-time-unknown-001",
    "caseFamily": "ea-search-time-unknown",
    "split": "DEV",
    "origin": "SYNTHETIC",
    "actor": {"role": "EMPLOYEE", "primarySystemCodes": []},
    "firstMessage": "지난달 결재 문서가 안 보여요",
    "userKnowledge": {
        "systemCode": "ELECTRONIC_APPROVAL",
        "taskOrScreen": "전자결재 문서 조회 화면에서 지난달 문서를 찾으려 해요",
        "occurredAt": None,
        "expectedBehavior": "지난달에 올린 문서가 목록에 보여야 해요",
    },
    "unknownFields": ["occurredAt"],
    "revealRule": "ASKED_FIELD_ONLY",
}


def ask(*fields: str, ref: str = "new:1") -> QuestionProposal:
    return QuestionProposal(
        problemRef=ref, bundleRef="b", text="질문", requestedFields=list(fields)
    )


def test_user_answers_only_the_asked_fields() -> None:
    scenario = parse_scenario(SCENARIO)

    reply = reply_to(scenario, [ask("taskOrScreen")])

    assert "전자결재 문서 조회 화면" in reply.text
    assert "지난달에 올린 문서" not in reply.text  # 묻지 않은 expectedBehavior는 공개하지 않는다
    assert reply.unknown == ()


def test_user_says_they_do_not_know_fields_listed_as_unknown() -> None:
    scenario = parse_scenario(SCENARIO)

    reply = reply_to(scenario, [ask("occurredAt")])

    assert "모르겠어요" in reply.text
    assert reply.unknown == (("new:1", "occurredAt"),)


def test_field_without_a_known_value_is_answered_as_unknown() -> None:
    scenario = parse_scenario(SCENARIO)

    reply = reply_to(scenario, [ask("businessPurpose")])

    assert "업무 목적" in reply.text and "모르겠어요" in reply.text
    assert reply.unknown == (("new:1", "businessPurpose"),)


def test_system_question_is_answered_with_the_system_name() -> None:
    scenario = parse_scenario(SCENARIO)

    assert "전자결재" in reply_to(scenario, [ask("system")]).text


def test_same_question_gets_the_same_answer() -> None:
    scenario = parse_scenario(SCENARIO)

    first = reply_to(scenario, [ask("taskOrScreen", "occurredAt")])
    second = reply_to(scenario, [ask("taskOrScreen", "occurredAt")])

    assert first == second


@pytest.mark.parametrize(
    ("change", "message"),
    [
        ({"split": "PROD"}, "split"),
        ({"revealRule": "ALWAYS"}, "revealRule"),
        ({"firstMessage": "  "}, "firstMessage"),
        ({"userKnowledge": {"unknownThing": "x"}}, "userKnowledge"),
        ({"unknownFields": ["taskOrScreen"]}, "겹친다"),
    ],
)
def test_scenario_format_errors_are_rejected(change, message) -> None:
    with pytest.raises(ScenarioFormatError, match=message):
        parse_scenario({**SCENARIO, **change})


def test_scenario_file_loader_reads_json(tmp_path) -> None:
    path = tmp_path / "s.json"
    path.write_text(json.dumps(SCENARIO, ensure_ascii=False), encoding="utf-8")

    assert load_scenario(path).case_family == "ea-search-time-unknown"
