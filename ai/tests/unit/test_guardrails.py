import json
from pathlib import Path

from hops_ai.contracts.agent_1 import agent_request_adapter, agent_result_adapter
from hops_ai.harness.guardrails.input import InputGuard, NoopInputGuard
from hops_ai.harness.guardrails.output import NoopOutputGuard, OutputGuard

FIXTURE = Path(__file__).resolve().parents[1] / "fixtures" / "agent_1" / "intake.json"


def load_case():
    case = json.loads(FIXTURE.read_text(encoding="utf-8"))
    return (
        agent_request_adapter.validate_python(case["request"]),
        agent_result_adapter.validate_python(case["result"]),
    )


def test_noop_guards_satisfy_the_guard_interfaces() -> None:
    assert isinstance(NoopInputGuard(), InputGuard)
    assert isinstance(NoopOutputGuard(), OutputGuard)


def test_noop_input_guard_returns_the_messages_unchanged() -> None:
    request, _ = load_case()

    checked = NoopInputGuard().check(request.payload.messages)

    assert checked == request.payload.messages


def test_noop_output_guard_returns_the_result_unchanged() -> None:
    request, result = load_case()

    assert NoopOutputGuard().check(request, result) is result
