"""서버 로그에 남기는 실패 요약이 원인은 알려 주되 사용자 원문은 싣지 않는지 확인한다."""

from pydantic import BaseModel, ValidationError

from hops_ai.harness.errors import OutputRejected
from hops_ai.supervisor.runtime import describe_failure_for_log


def raised(error: Exception) -> Exception:
    try:
        raise error
    except Exception as caught:
        return caught


def test_harness_error_keeps_its_fixed_detail_and_the_raise_location() -> None:
    summary = describe_failure_for_log(
        raised(OutputRejected("티켓으로 제안할 수 있는 문제가 없다"))
    )

    assert summary.startswith("OutputRejected at test_failure_log.py:")
    assert summary.endswith("티켓으로 제안할 수 있는 문제가 없다")


def test_validation_error_does_not_leak_the_rejected_values() -> None:
    class Reply(BaseModel):
        number: int

    try:
        Reply.model_validate({"number": "사용자가 쓴 비밀 문장"})
    except ValidationError as error:
        summary = describe_failure_for_log(error)

    assert summary.startswith("ValidationError")
    assert "사용자가 쓴 비밀 문장" not in summary


def test_plain_exception_message_is_not_logged() -> None:
    summary = describe_failure_for_log(raised(RuntimeError("사용자 문장이 섞였을 수 있는 설명")))

    assert summary.startswith("RuntimeError")
    assert "사용자 문장" not in summary


def test_long_detail_is_cut() -> None:
    summary = describe_failure_for_log(raised(OutputRejected("가" * 500)))

    assert len(summary) < 300
