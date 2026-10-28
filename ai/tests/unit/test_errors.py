import pytest

from hops_ai.contracts.agent_1 import AgentError
from hops_ai.harness.errors import (
    BudgetExceeded,
    CallTimeoutError,
    DeadlineExceeded,
    HarnessError,
    InputRejected,
    LlmCallLimitExceeded,
    OutputRejected,
    PolicyDenied,
    RunCancelled,
    TransientError,
    describe_failure,
)


@pytest.mark.parametrize(
    ("error", "status", "code", "retryable"),
    [
        (DeadlineExceeded(), "TIMED_OUT", "DEADLINE_EXCEEDED", False),
        (LlmCallLimitExceeded(), "ERROR", "BUDGET_EXCEEDED", False),
        (RunCancelled(), "CANCELLED", "SUPERSEDED", False),
        (PolicyDenied(), "ERROR", "ACCESS_DENIED", False),
        (InputRejected(), "ERROR", "INVALID_INPUT", False),
        (OutputRejected(), "ERROR", "INVALID_OUTPUT", False),
        (TransientError(), "ERROR", "MODEL_UNAVAILABLE", True),
        (CallTimeoutError(), "ERROR", "MODEL_TIMEOUT", True),
    ],
)
def test_harness_error_maps_to_contract_failure(
    error: HarnessError, status: str, code: str, retryable: bool
) -> None:
    failure = describe_failure(error)

    assert failure.status == status
    assert failure.error.code == code
    assert failure.error.retryable is retryable
    assert isinstance(failure.error, AgentError)


def test_only_transient_errors_are_retryable() -> None:
    all_errors = [
        DeadlineExceeded(),
        LlmCallLimitExceeded(),
        RunCancelled(),
        PolicyDenied(),
        InputRejected(),
        OutputRejected(),
        TransientError(),
        CallTimeoutError(),
    ]

    retryable = {type(error).__name__ for error in all_errors if error.retryable}

    assert retryable == {"TransientError", "CallTimeoutError"}


def test_budget_errors_share_a_base_class() -> None:
    assert issubclass(DeadlineExceeded, BudgetExceeded)
    assert issubclass(LlmCallLimitExceeded, BudgetExceeded)


def test_unknown_exception_becomes_internal_error_without_leaking_details() -> None:
    failure = describe_failure(RuntimeError("Authorization: Bearer secret-token"))

    assert failure.status == "ERROR"
    assert failure.error.code == "INTERNAL_ERROR"
    assert failure.error.retryable is False
    assert "secret-token" not in failure.error.message


def test_detail_is_for_logs_and_is_not_put_into_the_contract_error() -> None:
    error = PolicyDenied("INTAKE 역할은 read_logs 도구를 쓸 수 없다")

    failure = describe_failure(error)

    assert error.detail in str(error)
    assert failure.error.message == PolicyDenied.safe_message
    assert "read_logs" not in failure.error.message


def test_retry_after_is_kept_on_transient_errors() -> None:
    assert TransientError(retry_after=3.0).retry_after == 3.0
    assert TransientError().retry_after is None
