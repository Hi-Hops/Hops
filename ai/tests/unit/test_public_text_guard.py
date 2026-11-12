"""공통 검사에서는 비밀값만 거부하고 기존 업무·형식 규칙은 대체하지 않는다."""

import pytest

from hops_ai.contracts.domain import AgentRole
from hops_ai.harness.errors import OutputRejected, describe_failure
from hops_ai.harness.guardrails.events import GuardrailStage
from hops_ai.harness.guardrails.public_text import validate_public_text
from tests.supervisor_fixtures import uid


@pytest.mark.parametrize("stage", list(GuardrailStage))
def test_secret_is_rejected_with_safe_error_and_log(stage, caplog):
    secret = "sk-proj-" + "x" * 30
    text = "공개해서는 안 되는 합성 원문 " + secret

    with pytest.raises(OutputRejected) as caught:
        validate_public_text(text, stage=stage, run_id=uid(100), role=AgentRole.INQUIRY)

    failure = describe_failure(caught.value)
    assert failure.error.code == "INVALID_OUTPUT"
    assert failure.error.retryable is False
    assert text not in caplog.text
    assert secret not in caplog.text + str(caught.value) + repr(failure)
    assert "공개해서는 안 되는 합성 원문" not in caplog.text


def test_same_kind_is_logged_once_even_with_multiple_values(caplog):
    with pytest.raises(OutputRejected):
        validate_public_text(
            "sk-" + "a" * 24 + " sk-" + "b" * 24,
            stage=GuardrailStage.GUIDE,
            run_id=uid(100),
        )

    assert len(caplog.records) == 1


def test_normal_security_guidance_is_not_mutated_or_logged(caplog):
    text = "비밀번호를 채팅에 입력하지 말고 공식 화면에서 재설정해 주세요."

    assert validate_public_text(text, stage=GuardrailStage.GUIDE, run_id=uid(100)) is None
    assert caplog.records == []
