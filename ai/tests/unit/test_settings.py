from pathlib import Path

import pytest
from pydantic import ValidationError

from hops_ai.settings import RunMode, Settings


def test_default_mode_never_enables_fake_business_execution(make_settings) -> None:
    assert make_settings().mode is RunMode.SCAFFOLD


def test_environment_overrides_budget(monkeypatch, make_settings) -> None:
    monkeypatch.setenv("HOPS_AI_MAX_LLM_CALLS", "3")
    assert make_settings().max_llm_calls == 3


@pytest.mark.parametrize(
    "overrides",
    [
        {"mode": "fake"},
        {"mode": "production"},
        {"agent_mode": "single"},
        {"agent_mode": "multi"},
        {"api_prefix": "/internal/mock/v1"},
        {"run_deadline_seconds": 0},
        {"max_llm_calls": 0},
        {"max_auto_retries": -1},
        {"max_output_tokens": 0},
        {"run_deadline_seconds": 10, "llm_call_timeout_seconds": 30},
    ],
)
def test_invalid_or_retired_configuration_is_rejected(make_settings, overrides) -> None:
    with pytest.raises(ValidationError):
        make_settings(**overrides)


def test_root_env_example_contains_all_current_ai_defaults(monkeypatch, make_settings) -> None:
    known = {f"HOPS_AI_{name.upper()}" for name in Settings.model_fields}
    defaults = make_settings().model_dump()
    example = Path(__file__).resolve().parents[3] / ".env.example"
    seen = set()
    for line in example.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line.startswith("HOPS_AI_"):
            name, value = line.split("=", 1)
            assert name in known
            assert name not in seen
            seen.add(name)
            monkeypatch.setenv(name, value)
    assert seen == known
    assert make_settings().model_dump() == defaults
