import asyncio
import json
from pathlib import Path

import pytest

from hops_ai.agents.base import Agent, Analyzer, execute_safely, failure_result, run_analysis
from hops_ai.contracts.agent_1 import AgentError, agent_request_adapter, agent_result_adapter
from hops_ai.contracts.analysis import AnalysisFailure, AnalysisOutcome
from hops_ai.harness.errors import (
    DeadlineExceeded,
    PolicyDenied,
    RoleFailed,
    RunCancelled,
    TransientError,
)

FIXTURES = Path(__file__).resolve().parents[1] / "fixtures" / "agent_1"
ROLES = ["intake", "knowledge", "diagnosis", "ticket"]


def case(role: str):
    data = json.loads((FIXTURES / f"{role}.json").read_text(encoding="utf-8"))
    return (
        agent_request_adapter.validate_python(data["request"]),
        agent_result_adapter.validate_python(data["result"]),
    )


class Returns:
    def __init__(self, result) -> None:
        self.result = result

    async def execute(self, request, services):
        return self.result


class Raises:
    def __init__(self, error: BaseException) -> None:
        self.error = error

    async def execute(self, request, services):
        raise self.error


async def test_good_result_passes_through_unchanged(make_services) -> None:
    request, result = case("intake")

    returned = await execute_safely(Returns(result), request, make_services())

    assert returned is result


@pytest.mark.parametrize("role", ROLES)
async def test_failure_result_has_the_requests_identifiers_and_role(role, make_services) -> None:
    request, _ = case(role)

    result = await execute_safely(Raises(RuntimeError("boom")), request, make_services())

    assert result.role == role.upper()
    assert (result.runId, result.invocationId) == (request.runId, request.invocationId)
    assert (result.inquiryId, result.inquiryVersion) == (request.inquiryId, request.inquiryVersion)
    assert result.status == "ERROR"
    assert result.data is None
    assert result.evidence == []
    # 만든 실패 결과도 계약을 통과해야 한다
    assert agent_result_adapter.validate_python(result.model_dump(mode="json")) == result


async def test_unexpected_exception_is_reported_as_internal_error_without_leaking(
    make_services,
) -> None:
    request, _ = case("intake")

    result = await execute_safely(
        Raises(RuntimeError("Authorization: Bearer secret-token")), request, make_services()
    )

    assert result.error == AgentError(
        code="INTERNAL_ERROR", message="내부 오류가 발생했다", retryable=False
    )
    assert "secret-token" not in result.model_dump_json()


@pytest.mark.parametrize(
    ("error", "status", "code"),
    [
        (PolicyDenied(), "ERROR", "ACCESS_DENIED"),
        (DeadlineExceeded(), "TIMED_OUT", "DEADLINE_EXCEEDED"),
        (RunCancelled(), "CANCELLED", "SUPERSEDED"),
        (TransientError(), "ERROR", "MODEL_UNAVAILABLE"),
    ],
)
async def test_known_failures_keep_their_status_and_code(
    error, status, code, make_services
) -> None:
    request, _ = case("knowledge")

    result = await execute_safely(Raises(error), request, make_services())

    assert (result.status, result.error.code) == (status, code)


async def test_duration_is_measured_from_the_runtime_clock(make_services, clock) -> None:
    request, _ = case("intake")

    class Slow:
        async def execute(self, request, services):
            clock.advance(2.5)
            raise RuntimeError

    result = await execute_safely(Slow(), request, make_services())

    assert result.meta.durationMs == 2500


async def test_result_with_another_invocation_is_rejected(make_services) -> None:
    request, result = case("intake")
    wrong = result.model_copy(update={"invocationId": "00000000-0000-4000-8000-000000000999"})

    returned = await execute_safely(Returns(wrong), request, make_services())

    assert returned.status == "ERROR"
    assert returned.error.code == "INVALID_OUTPUT"


async def test_result_of_another_role_is_rejected(make_services) -> None:
    request, _ = case("intake")
    _, knowledge_result = case("knowledge")

    returned = await execute_safely(Returns(knowledge_result), request, make_services())

    assert returned.error.code == "INVALID_OUTPUT"
    assert returned.role == "INTAKE"


async def test_cancellation_is_not_swallowed(make_services) -> None:
    request, _ = case("intake")

    with pytest.raises(asyncio.CancelledError):
        await execute_safely(Raises(asyncio.CancelledError()), request, make_services())


def test_failure_result_builder_accepts_any_exception(make_services) -> None:
    request, _ = case("ticket")

    result = failure_result(request, make_services(), 0.0, ValueError("x"))

    assert result.role == "TICKET"
    assert result.error.code == "INTERNAL_ERROR"


# ------------------------------------------------------------------ 분석 전체


class Analyzes:
    def __init__(self, outcome) -> None:
        self.outcome = outcome

    async def analyze(self, data, services):
        return self.outcome


class AnalyzeRaises:
    def __init__(self, error: BaseException) -> None:
        self.error = error

    async def analyze(self, data, services):
        raise self.error


async def test_run_analysis_returns_the_outcome(make_services, make_input) -> None:
    outcome = AnalysisOutcome(answer="답", problems=[], ticket_proposals=[], evidence=[])

    result = await run_analysis(Analyzes(outcome), make_input(), make_services())

    assert result is outcome


async def test_run_analysis_turns_harness_errors_into_a_failure(make_services, make_input) -> None:
    result = await run_analysis(AnalyzeRaises(DeadlineExceeded()), make_input(), make_services())

    assert isinstance(result, AnalysisFailure)
    assert (result.status, result.error.code) == ("TIMED_OUT", "DEADLINE_EXCEEDED")


async def test_run_analysis_hides_unexpected_exceptions(make_services, make_input) -> None:
    result = await run_analysis(
        AnalyzeRaises(RuntimeError("api key sk-secret")), make_input(), make_services()
    )

    assert isinstance(result, AnalysisFailure)
    assert result.error.code == "INTERNAL_ERROR"
    assert "sk-secret" not in result.error.message


async def test_run_analysis_keeps_the_failed_roles_status_and_error(
    make_services, make_input
) -> None:
    error = AgentError(code="MODEL_UNAVAILABLE", message="모델 연결 실패", retryable=True)

    result = await run_analysis(
        AnalyzeRaises(RoleFailed("ERROR", error)), make_input(), make_services()
    )

    assert result == AnalysisFailure(status="ERROR", error=error)


async def test_run_analysis_does_not_swallow_cancellation(make_services, make_input) -> None:
    with pytest.raises(asyncio.CancelledError):
        await run_analysis(AnalyzeRaises(asyncio.CancelledError()), make_input(), make_services())


def test_protocols_are_runtime_checkable() -> None:
    assert isinstance(Returns(None), Agent)
    assert isinstance(Analyzes(None), Analyzer)
    assert not isinstance(object(), Agent)
