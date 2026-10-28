"""agent-1(Supervisor와 4개 역할 사이의 내부 계약) 정상·오류 사례를 검사한다.

원본은 contracts/agents/README.md다. 입출력 형식만 확인하며 에이전트 동작은 검증하지 않는다.
"""

import copy
import json
from pathlib import Path
from typing import Any

import pytest
from pydantic import ValidationError

from hops_ai.contracts.agent_1 import agent_request_adapter, agent_result_adapter

FIXTURE_DIR = Path(__file__).resolve().parents[1] / "fixtures" / "agent_1"
ROLES = ["intake", "knowledge", "diagnosis", "ticket"]


def load(name: str) -> Any:
    return json.loads((FIXTURE_DIR / f"{name}.json").read_text(encoding="utf-8"))


def request_of(role: str) -> dict[str, Any]:
    return copy.deepcopy(load(role)["request"])


def result_of(role: str) -> dict[str, Any]:
    return copy.deepcopy(load(role)["result"])


@pytest.mark.parametrize("role", ROLES)
def test_request_fixture_validates_and_round_trips(role: str) -> None:
    value = request_of(role)

    model = agent_request_adapter.validate_python(value)

    assert model.role == role.upper()
    assert model.model_dump(mode="json") == value


@pytest.mark.parametrize("role", ROLES)
def test_result_fixture_validates_and_round_trips(role: str) -> None:
    value = result_of(role)

    model = agent_result_adapter.validate_python(value)

    assert model.role == role.upper()
    assert model.status == "OK"
    assert model.model_dump(mode="json") == value


@pytest.mark.parametrize("case", load("failures"), ids=lambda case: case["id"])
def test_failure_fixture_validates_without_data(case: dict[str, Any]) -> None:
    model = agent_result_adapter.validate_python(case["result"])

    assert model.status != "OK"
    assert model.data is None
    assert model.error is not None
    assert model.model_dump(mode="json") == case["result"]


def test_only_transient_failures_are_marked_retryable() -> None:
    retryable = {case["id"]: case["result"]["error"]["retryable"] for case in load("failures")}

    assert retryable == {
        "timed-out": False,
        "model-unavailable": True,
        "retrieval-unavailable": True,
        "tool-denied": False,
        "superseded": False,
    }


# ------------------------------------------------------------------ 요청 검증


def test_request_unknown_field_is_rejected() -> None:
    value = request_of("intake")
    value["payload"]["extra"] = 1

    with pytest.raises(ValidationError):
        agent_request_adapter.validate_python(value)


def test_request_contract_version_must_be_agent_1() -> None:
    value = request_of("intake")
    value["contractVersion"] = "agent-2"

    with pytest.raises(ValidationError):
        agent_request_adapter.validate_python(value)


def test_request_unknown_role_is_rejected() -> None:
    value = request_of("intake")
    value["role"] = "PLANNER"

    with pytest.raises(ValidationError):
        agent_request_adapter.validate_python(value)


def test_request_payload_must_match_its_role() -> None:
    value = request_of("intake")
    value["payload"] = request_of("knowledge")["payload"]

    with pytest.raises(ValidationError):
        agent_request_adapter.validate_python(value)


def test_request_requires_invocation_id() -> None:
    value = request_of("knowledge")
    del value["invocationId"]

    with pytest.raises(ValidationError):
        agent_request_adapter.validate_python(value)


def test_request_deadline_is_required_so_roles_share_one_deadline() -> None:
    value = request_of("knowledge")
    del value["context"]["deadlineAt"]

    with pytest.raises(ValidationError):
        agent_request_adapter.validate_python(value)


def test_agent_message_sender_is_assistant_not_ai() -> None:
    value = request_of("intake")
    value["payload"]["messages"][0]["sender"] = "AI"

    with pytest.raises(ValidationError):
        agent_request_adapter.validate_python(value)


def test_problem_question_count_above_three_is_rejected() -> None:
    value = request_of("knowledge")
    value["payload"]["problem"]["questionCount"] = 4

    with pytest.raises(ValidationError):
        agent_request_adapter.validate_python(value)


def test_tool_evidence_requires_observed_at() -> None:
    value = request_of("diagnosis")
    value["payload"]["knownEvidence"][0]["kind"] = "TOOL"  # 문서 근거를 도구 근거로 바꾼다

    with pytest.raises(ValidationError):
        agent_request_adapter.validate_python(value)


def test_document_evidence_does_not_need_observed_at() -> None:
    agent_request_adapter.validate_python(request_of("diagnosis"))


# ------------------------------------------------------------------ 결과 검증


def test_ok_result_requires_data() -> None:
    value = result_of("knowledge")
    value["data"] = None

    with pytest.raises(ValidationError):
        agent_result_adapter.validate_python(value)


def test_ok_result_must_not_carry_an_error() -> None:
    value = result_of("knowledge")
    value["error"] = {"code": "INTERNAL_ERROR", "message": "오류", "retryable": False}

    with pytest.raises(ValidationError):
        agent_result_adapter.validate_python(value)


def test_failed_result_must_not_carry_data() -> None:
    value = load("failures")[1]["result"]
    value["data"] = result_of("knowledge")["data"]

    with pytest.raises(ValidationError):
        agent_result_adapter.validate_python(value)


def test_failed_result_requires_an_error() -> None:
    value = load("failures")[0]["result"]
    value["error"] = None

    with pytest.raises(ValidationError):
        agent_result_adapter.validate_python(value)


def test_result_data_must_match_its_role() -> None:
    value = result_of("intake")
    value["data"] = result_of("knowledge")["data"]

    with pytest.raises(ValidationError):
        agent_result_adapter.validate_python(value)


def test_result_unknown_error_code_is_rejected() -> None:
    value = load("failures")[0]["result"]
    value["error"]["code"] = "SOMETHING_ELSE"

    with pytest.raises(ValidationError):
        agent_result_adapter.validate_python(value)


def test_result_meta_is_required_and_duration_is_not_negative() -> None:
    value = result_of("intake")
    value["meta"]["durationMs"] = -1

    with pytest.raises(ValidationError):
        agent_result_adapter.validate_python(value)


def test_result_meta_keys_must_be_sent_even_when_model_is_unused() -> None:
    value = result_of("intake")
    del value["meta"]["modelId"]

    with pytest.raises(ValidationError):
        agent_result_adapter.validate_python(value)


@pytest.mark.parametrize("question", [None, "", "   "])
def test_ask_user_requires_a_question(question: str | None) -> None:
    value = result_of("intake")
    value["data"]["problems"][0]["question"] = question

    with pytest.raises(ValidationError):
        agent_result_adapter.validate_python(value)


def test_non_question_actions_may_have_no_question() -> None:
    value = result_of("intake")
    problem = value["data"]["problems"][0]
    problem["nextAction"] = "PROPOSE_TICKETS"
    problem["question"] = None

    agent_result_adapter.validate_python(value)


def test_ticket_result_can_report_unresolved_problems_instead_of_hiding_them() -> None:
    value = result_of("ticket")
    value["data"]["ticketProposals"] = []
    value["data"]["unresolvedProblemRefs"] = ["00000000-0000-4000-8000-000000000201"]

    model = agent_result_adapter.validate_python(value)

    assert model.data.unresolvedProblemRefs == ["00000000-0000-4000-8000-000000000201"]


def test_ticket_proposal_needs_a_known_purpose() -> None:
    value = result_of("ticket")
    value["data"]["ticketProposals"][0]["purpose"] = "URGENT"

    with pytest.raises(ValidationError):
        agent_result_adapter.validate_python(value)
