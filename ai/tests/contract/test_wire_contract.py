"""서비스 간 계약(AiRequest·AiResult 등)이 공용 Mock 예시와 계약 규칙에 맞는지 검사한다.

예시는 팀이 공유하는 specs/mocks/fixtures.json을 그대로 읽는다. 이 검사는 입출력 형식만 확인하며
실제 모델 호출·Spring 연동·E2E를 검증하지 않는다.
"""

import copy
import json
from collections.abc import Callable
from pathlib import Path
from typing import Any

import pytest
from pydantic import ValidationError

from hops_ai.api.schemas import (
    AiAccepted,
    AiDelta,
    AiProgress,
    AiRequest,
    ai_result_adapter,
)

FIXTURES = Path(__file__).resolve().parents[3] / "specs" / "mocks" / "fixtures.json"

VALIDATORS: dict[str, Callable[[Any], Any]] = {
    "AiRequest": AiRequest.model_validate,
    "AiAccepted": AiAccepted.model_validate,
    "AiProgress": AiProgress.model_validate,
    "AiDelta": AiDelta.model_validate,
    "AiResult": ai_result_adapter.validate_python,
}


def load_cases() -> list[dict[str, Any]]:
    cases = json.loads(FIXTURES.read_text(encoding="utf-8"))["cases"]
    return [case for case in cases if case["type"] in VALIDATORS]


CASES = load_cases()


def value_of(case_id: str) -> dict[str, Any]:
    for case in CASES:
        if case["id"] == case_id:
            return copy.deepcopy(case["value"])
    raise KeyError(case_id)


@pytest.mark.parametrize("case", CASES, ids=lambda case: case["id"])
def test_shared_mock_fixture_validates_and_round_trips(case: dict[str, Any]) -> None:
    model = VALIDATORS[case["type"]](case["value"])

    # 필드가 빠지거나 더해지지 않고 그대로 되돌아와야 한다
    assert model.model_dump(mode="json") == case["value"]


def test_shared_fixtures_cover_every_ai_contract_type() -> None:
    assert {case["type"] for case in CASES} == set(VALIDATORS)


def test_result_variants_are_distinguished_by_status() -> None:
    assert type(ai_result_adapter.validate_python(value_of("ai-ask"))).__name__ == "AiSuccess"
    assert type(ai_result_adapter.validate_python(value_of("ai-timeout"))).__name__ == "AiFailure"


def test_unknown_field_is_rejected() -> None:
    value = value_of("ai-request")
    value["unexpected"] = 1

    with pytest.raises(ValidationError):
        AiRequest.model_validate(value)


def test_unknown_nested_field_is_rejected() -> None:
    value = value_of("ai-request")
    value["actor"]["token"] = "secret"

    with pytest.raises(ValidationError):
        AiRequest.model_validate(value)


def test_nullable_field_must_be_sent_as_null_not_omitted() -> None:
    value = value_of("ai-request")
    del value["evidence"][0]["observedAt"]

    with pytest.raises(ValidationError):
        AiRequest.model_validate(value)


def test_nullable_field_accepts_null() -> None:
    value = value_of("ai-ask")
    value["problems"][0]["category"] = None

    ai_result_adapter.validate_python(value)


def test_success_must_not_carry_an_error() -> None:
    value = value_of("ai-ask")
    value["error"] = value_of("ai-timeout")["error"]

    with pytest.raises(ValidationError):
        ai_result_adapter.validate_python(value)


def test_failure_must_not_carry_success_fields() -> None:
    value = value_of("ai-timeout")
    value["answer"] = "성공한 것처럼 보이는 답변"

    with pytest.raises(ValidationError):
        ai_result_adapter.validate_python(value)


def test_failure_requires_an_error_body() -> None:
    value = value_of("ai-timeout")
    del value["error"]

    with pytest.raises(ValidationError):
        ai_result_adapter.validate_python(value)


@pytest.mark.parametrize("status", ["DONE", "ACCEPTED", "ok", ""])
def test_unknown_result_status_is_rejected(status: str) -> None:
    value = value_of("ai-ask")
    value["status"] = status

    with pytest.raises(ValidationError):
        ai_result_adapter.validate_python(value)


def test_schema_version_must_match() -> None:
    value = value_of("ai-accepted")
    value["schemaVersion"] = "hops-mock-2"

    with pytest.raises(ValidationError):
        AiAccepted.model_validate(value)


@pytest.mark.parametrize(
    "run_id",
    [
        "00000000-0000-4000-8000-00000000030A",  # 대문자
        "00000000000040008000000000000300",  # 하이픈 없음
        "not-a-uuid",
        "",
    ],
)
def test_uuid_must_be_lowercase_hyphenated(run_id: str) -> None:
    value = value_of("ai-accepted")
    value["runId"] = run_id

    with pytest.raises(ValidationError):
        AiAccepted.model_validate(value)


@pytest.mark.parametrize("version", [0, -1, 2_147_483_648, "1", 1.5, True, None])
def test_inquiry_version_must_be_a_positive_integer(version: object) -> None:
    value = value_of("ai-accepted")
    value["inquiryVersion"] = version

    with pytest.raises(ValidationError):
        AiAccepted.model_validate(value)


@pytest.mark.parametrize("ref", ["new:", "vpn", "new:a b", "NEW:vpn", ""])
def test_invalid_ref_is_rejected(ref: str) -> None:
    value = value_of("ai-ask")
    value["problems"][0]["ref"] = ref

    with pytest.raises(ValidationError):
        ai_result_adapter.validate_python(value)


@pytest.mark.parametrize("ref", ["new:vpn", "new:work_1-a", "00000000-0000-4000-8000-000000000201"])
def test_valid_ref_is_accepted(ref: str) -> None:
    value = value_of("ai-ask")
    value["problems"][0]["ref"] = ref

    ai_result_adapter.validate_python(value)


def test_question_count_above_three_is_rejected() -> None:
    value = value_of("ai-ask")
    value["problems"][0]["questionCount"] = 4

    with pytest.raises(ValidationError):
        ai_result_adapter.validate_python(value)


@pytest.mark.parametrize(
    "deadline",
    [
        "2026-13-01T00:00:00Z",  # 없는 달
        "2026-10-23T00:00:00+09:00",  # UTC가 아님
        "2026-10-23 00:00:00Z",
        "2026-10-23T00:00:00.1234567Z",  # 소수초 7자리
        "2026-10-23T00:00:00",
    ],
)
def test_deadline_must_be_a_valid_utc_timestamp(deadline: str) -> None:
    value = value_of("ai-request")
    value["deadlineAt"] = deadline

    with pytest.raises(ValidationError):
        AiRequest.model_validate(value)


@pytest.mark.parametrize("deadline", ["2026-10-23T00:01:00Z", "2026-10-23T00:01:00.123456Z"])
def test_valid_utc_timestamp_is_accepted(deadline: str) -> None:
    value = value_of("ai-request")
    value["deadlineAt"] = deadline

    AiRequest.model_validate(value)


def test_unknown_evidence_kind_is_rejected() -> None:
    value = value_of("ai-request")
    value["evidence"][0]["kind"] = "RUMOR"

    with pytest.raises(ValidationError):
        AiRequest.model_validate(value)


def test_unknown_error_code_is_rejected() -> None:
    value = value_of("ai-timeout")
    value["error"]["code"] = "SOMETHING_ELSE"

    with pytest.raises(ValidationError):
        ai_result_adapter.validate_python(value)


def test_ai_sender_must_be_user_or_ai_in_wire_messages() -> None:
    value = value_of("ai-request-tickets")
    value["messages"][1]["sender"] = (
        "ASSISTANT"  # 내부 agent-1 표기는 서비스 간 계약에서 쓰지 않는다
    )

    with pytest.raises(ValidationError):
        AiRequest.model_validate(value)


def test_answer_delta_sequence_starts_at_one() -> None:
    value = value_of("ai-delta")
    value["payload"]["seq"] = 0

    with pytest.raises(ValidationError):
        AiDelta.model_validate(value)
