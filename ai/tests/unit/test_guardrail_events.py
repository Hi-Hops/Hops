"""로그 입력은 닫힌 어휘·UUID로 제한하며 원문을 받지 않는다."""

import pytest

from hops_ai.contracts.domain import AgentRole
from hops_ai.harness.guardrails.events import GuardrailStage, record_guardrail_event
from hops_ai.harness.guardrails.sensitive_data import SecretKind
from tests.supervisor_fixtures import uid


def test_block_event_has_rule_stage_role_run_and_reason(caplog):
    record_guardrail_event(
        stage=GuardrailStage.QUESTION,
        kind=SecretKind.OPENAI_KEY,
        role=AgentRole.INQUIRY,
        run_id=uid(100),
    )

    [record] = caplog.records
    assert record.getMessage() == (
        f"guardrail_blocked rule=SEC-02 stage=QUESTION role=INQUIRY "
        f"run_id={uid(100)} reason=OPENAI_KEY"
    )
    assert record.exc_info is None


def test_final_answer_uses_supervisor_role(caplog):
    record_guardrail_event(stage=GuardrailStage.FINAL_ANSWER, kind=SecretKind.JWT, run_id=uid(100))

    assert "role=SUPERVISOR" in caplog.text


@pytest.mark.parametrize("field", ["stage", "kind", "role", "run_id"])
def test_free_text_context_never_enters_the_log(field, caplog):
    fields = dict(
        stage=GuardrailStage.GUIDE,
        kind=SecretKind.BEARER_TOKEN,
        role=AgentRole.KNOWLEDGE,
        run_id=uid(100),
    )
    fields[field] = "untrusted\n" + "sk-" + "z" * 24

    with pytest.raises(ValueError):
        record_guardrail_event(**fields)

    assert caplog.records == []
