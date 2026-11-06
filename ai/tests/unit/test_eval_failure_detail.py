"""역할 실패의 사유 기록. 어느 검증이 거부했는지 구분하려고 trace와 보고서에 남긴다."""

import json

import pytest

from evaluation.harness.recorders import MAX_FAILURE_DETAIL, CallLog, RecordingAgent
from evaluation.harness.trace import RoleCall
from evaluation.harness.trace_io import trace_from_dict, trace_to_dict
from evaluation.pilot import _turn_summary
from hops_ai.contracts.domain import AgentRole
from hops_ai.harness.errors import OutputRejected, RetrievalUnavailable
from tests.eval_fixtures import ai_result, request_with, run_trace, turn


class Raises:
    role = AgentRole.TICKET

    def __init__(self, error: Exception) -> None:
        self._error = error

    async def run(self, request, services):
        raise self._error


async def recorded(error: Exception) -> RoleCall:
    log = CallLog()
    with pytest.raises(type(error)):
        await RecordingAgent(Raises(error), log).run(None, None)
    [call] = log.role_calls
    return call


async def test_the_reason_of_a_rejected_output_is_recorded() -> None:
    call = await recorded(OutputRejected("관련 지식 참조가 중복되거나 허용되지 않았다"))

    assert call.failed_with == "OutputRejected"
    assert call.failure_detail == "관련 지식 참조가 중복되거나 허용되지 않았다"


async def test_a_harness_error_without_detail_records_its_fixed_message() -> None:
    call = await recorded(OutputRejected())

    assert call.failure_detail == OutputRejected.safe_message


async def test_a_long_reason_is_cut() -> None:
    call = await recorded(OutputRejected("가" * 1000))

    assert len(call.failure_detail) == MAX_FAILURE_DETAIL


async def test_an_unexpected_exception_records_only_its_type() -> None:
    call = await recorded(RuntimeError("사용자 원문이 섞였을 수 있는 메시지"))

    assert call.failed_with == "RuntimeError" and call.failure_detail is None


async def test_a_search_outage_keeps_its_own_record() -> None:
    log = CallLog()
    agent = RecordingAgent(Raises(RetrievalUnavailable()), log)
    agent.role = AgentRole.KNOWLEDGE
    with pytest.raises(RetrievalUnavailable):
        await agent.run(None, None)

    assert log.role_calls[0].search_state == "ERROR"
    assert log.role_calls[0].failure_detail is None


def failed_trace(detail: str | None = "책임 판단의 인용이 허용된 원문에 없다"):
    request = request_with()
    call = RoleCall(AgentRole.TICKET, failed_with="OutputRejected", failure_detail=detail)
    return run_trace(turn(request, ai_result(request, status="FAILED"), calls=[call]))


def test_the_reason_survives_the_trace_round_trip() -> None:
    trace = failed_trace()

    restored = trace_from_dict(json.loads(json.dumps(trace_to_dict(trace), ensure_ascii=False)))

    assert restored == trace
    assert restored.turns[0].role_calls[0].failure_detail == "책임 판단의 인용이 허용된 원문에 없다"


def test_a_trace_saved_before_the_reason_was_recorded_still_loads() -> None:
    data = trace_to_dict(failed_trace())
    for call in data["turns"][0]["role_calls"]:
        del call["failure_detail"]

    restored = trace_from_dict(data)

    assert restored.turns[0].role_calls[0].failure_detail is None


def test_the_report_lists_role_failures_with_their_reason() -> None:
    summary = _turn_summary(1, failed_trace().turns[0])

    assert summary["roles"] == ["TICKET:FAILED"]
    assert summary["roleFailures"] == [
        {
            "role": "TICKET",
            "type": "OutputRejected",
            "detail": "책임 판단의 인용이 허용된 원문에 없다",
        }
    ]


def test_a_turn_without_failures_has_no_failure_list() -> None:
    request = request_with()
    summary = _turn_summary(1, turn(request, ai_result(request)))

    assert "roleFailures" not in summary
