"""입력 스냅샷 보존·상태 전이·누적 한도 검증."""

from copy import deepcopy
from datetime import UTC, datetime, timedelta

import pytest
from pydantic import ValidationError

from hops_ai.contracts.execution import RunKind
from hops_ai.contracts.wire import AiError, AiRunRequest
from hops_ai.harness.errors import (
    BudgetExceeded,
    DeadlineExceeded,
    InputRejected,
    OutputRejected,
    RunCancelled,
)
from hops_ai.supervisor.state import create_initial_state, transition_phase
from tests.supervisor_fixtures import PROBLEM, SYSTEM, make_request, running_state, uid


def test_initial_state_preserves_server_data_and_detaches_input() -> None:
    """새 실행의 작업 목록은 비우되 기존 질문 수와 입력 ID를 복사해 보존한다."""
    request = make_request(question_count=4, outcome="STORED")
    state = create_initial_state(request)
    request.problemIds.clear()
    assert state["phase"] == "QUEUED"
    assert state["input"].problemIds == [PROBLEM]
    assert state["input"].problems[0].questionCount == 4
    assert state["input"].existingWorkItems[0].ticketId == uid(300)
    assert state["problems"][PROBLEM].problemRef == PROBLEM
    assert state["usage"] == {
        "calls": 0,
        "inputTokens": 0,
        "outputTokens": 0,
        "toolCalls": 0,
        "retries": 0,
    }
    assert state["questions"] == state["ticketProposals"] == state["roleResults"] == {}
    assert state["finalResult"] is None


def test_new_inquiry_does_not_invent_persistent_problem_ids() -> None:
    """처음 접수된 문의는 기존 문제 없이 시작하며 DB UUID를 만들어내지 않는다."""
    payload = make_request().model_dump(mode="json")
    payload.update(problems=[], problemIds=[])
    payload["messages"][0]["problemIds"] = []
    assert create_initial_state(AiRunRequest.model_validate(payload))["problems"] == {}


@pytest.mark.parametrize(
    "target", ["duplicate_message", "unknown_problem", "unknown_system", "duplicate_work"]
)
def test_invalid_input_relations_are_rejected(target) -> None:
    """형식이 맞더라도 중복 ID나 실제 입력에 없는 참조는 초기화에서 거부한다."""
    payload = make_request(outcome="STORED").model_dump(mode="json")
    if target == "duplicate_message":
        payload["messages"].append(deepcopy(payload["messages"][0]))
    elif target == "unknown_problem":
        payload["problemIds"] = [uid(999)]
    elif target == "unknown_system":
        payload["accessContext"]["systemIds"] = [uid(999)]
    else:
        payload["existingWorkItems"].append(deepcopy(payload["existingWorkItems"][0]))
    with pytest.raises(InputRejected):
        create_initial_state(AiRunRequest.model_validate(payload))


def test_employee_run_cannot_request_internal_staff_scope() -> None:
    """직원 상담 목적에 내부 담당자 자료 범위를 덧붙이지 못한다."""
    payload = make_request().model_dump(mode="json")
    payload["accessContext"]["allowedDocumentScopes"].append("SYSTEM_STAFF")
    with pytest.raises(InputRejected):
        create_initial_state(AiRunRequest.model_validate(payload))


def test_case_run_rejects_unrelated_message_context() -> None:
    """Curator 입력에는 원 처리 기록 문제에 연결된 원문만 제공한다."""
    payload = make_request(kind=RunKind.CASE_DRAFT).model_dump(mode="json")
    payload["messages"][0]["problemIds"] = []
    with pytest.raises(InputRejected):
        create_initial_state(AiRunRequest.model_validate(payload))


def test_transition_returns_new_state_and_preserves_usage() -> None:
    """상태를 바꿔도 원래 객체나 누적 사용량은 초기화하지 않는다."""
    state = running_state()
    state["usage"]["calls"] = 3
    updated = transition_phase(state, "FINALIZING")
    assert state["phase"] == "RUNNING"
    assert updated["phase"] == "FINALIZING"
    assert updated["usage"]["calls"] == 3
    assert updated["input"].systems[0].id == SYSTEM


@pytest.mark.parametrize("next_phase", ["QUEUED", "SUCCEEDED", "UNKNOWN"])
def test_invalid_phase_jump_is_rejected(next_phase) -> None:
    """뒤로 돌아가거나 검증된 결과 없이 성공하는 전이는 거부한다."""
    with pytest.raises(OutputRejected):
        transition_phase(running_state(), next_phase)


@pytest.mark.parametrize("phase", ["FAILED", "TIMED_OUT", "SUPERSEDED"])
def test_terminal_phase_cannot_resume(phase) -> None:
    """실패·기한 초과·대체된 실행을 다시 실행 상태로 되돌리지 못한다."""
    state = transition_phase(running_state(), phase)
    assert state["error"] is not None
    assert transition_phase(state, phase) is state
    with pytest.raises(OutputRejected):
        transition_phase(state, "RUNNING")


def test_pending_call_prevents_finalizing() -> None:
    """예약한 전문 역할의 실행이 남아 있으면 최종화 단계로 넘어가지 않는다."""
    state = running_state()
    state["pendingCalls"].add("pending")
    with pytest.raises(OutputRejected):
        transition_phase(state, "FINALIZING")


def test_expired_request_does_not_start() -> None:
    """Spring이 정한 전체 기한이 지난 입력은 실행을 시작하지 않는다."""
    payload = make_request().model_dump(mode="json")
    payload["expiresAt"] = (
        (datetime.now(UTC) - timedelta(seconds=1)).isoformat().replace("+00:00", "Z")
    )
    with pytest.raises(DeadlineExceeded):
        transition_phase(create_initial_state(AiRunRequest.model_validate(payload)), "RUNNING")


def test_cancelled_run_does_not_advance() -> None:
    """서버 취소 기록이 있으면 정상 전이를 차단한다."""
    state = running_state()
    state["cancellation"] = {"cancelled": True, "reason": "SUPERSEDED"}
    with pytest.raises(RunCancelled):
        transition_phase(state, "FINALIZING")


@pytest.mark.parametrize(
    "field,limit",
    [
        ("calls", 9),
        ("inputTokens", 12001),
        ("outputTokens", 4001),
        ("toolCalls", 11),
        ("retries", 3),
    ],
)
def test_all_cumulative_limits_are_checked(field, limit) -> None:
    """역할이 바뀌어도 모델·토큰·도구·재시도의 전체 누적 한도를 검사한다."""
    state = running_state()
    state["usage"][field] = limit
    with pytest.raises(BudgetExceeded):
        transition_phase(state, "FINALIZING")


@pytest.mark.parametrize(
    "mutation",
    [
        "missing_nullable",
        "extra_field",
        "boolean_integer",
        "wrong_kind_payload",
        "uppercase_uuid",
        "non_z_time",
        "blank_content",
        "surrogate_content",
    ],
)
def test_request_dto_rejects_invalid_wire_shapes(mutation) -> None:
    """필수 null 키·추가 필드·엄격한 정수·UUID·문자열 형식을 검사한다."""
    payload = make_request().model_dump(mode="json")
    if mutation == "missing_nullable":
        del payload["workRecord"]
    elif mutation == "extra_field":
        payload["modelId"] = "not-an-input-field"
    elif mutation == "boolean_integer":
        payload["inquiryVersion"] = True
    elif mutation == "wrong_kind_payload":
        payload["kind"] = "CASE_DRAFT"
    elif mutation == "uppercase_uuid":
        payload["runId"] = "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"
    elif mutation == "non_z_time":
        payload["expiresAt"] = "2026-11-02T00:00:00+00:00"
    elif mutation == "blank_content":
        payload["messages"][0]["content"] = "\ufeff \t"
    else:
        payload["messages"][0]["content"] = "\ud800"
    with pytest.raises(ValidationError):
        AiRunRequest.model_validate(payload)


def test_duplicate_message_problem_ids_are_rejected() -> None:
    """메시지 안의 문제 참조도 중복 없이 유지한다."""
    payload = make_request().model_dump(mode="json")
    payload["messages"][0]["problemIds"] = [PROBLEM, PROBLEM]
    with pytest.raises(InputRejected):
        create_initial_state(AiRunRequest.model_validate(payload))


@pytest.mark.parametrize("status", ["TIMED_OUT", "SUPERSEDED"])
def test_terminal_status_requires_corresponding_error_code(status) -> None:
    """시간 초과·대체 상태의 오류 코드는 Supervisor 상태 전이에서 대조한다."""
    with pytest.raises(OutputRejected):
        transition_phase(
            running_state(),
            status,
            error=AiError(code="INVALID_OUTPUT", message="잘못된 출력", retryable=False),
        )
