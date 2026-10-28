from pathlib import Path

import pytest
from pydantic import ValidationError

from hops_ai.settings import AgentMode, RunMode, Settings

ENV_EXAMPLE = Path(__file__).resolve().parents[2] / ".env.example"


def make_settings() -> Settings:
    # 개발자 PC의 .env 파일을 읽지 않도록 한다
    return Settings(_env_file=None)


def read_env_example() -> dict[str, str]:
    values = {}
    for line in ENV_EXAMPLE.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            name, _, value = line.partition("=")
            values[name] = value
    return values


def test_defaults_are_safe_and_match_agreed_budget() -> None:
    settings = make_settings()

    assert settings.mode is RunMode.FAKE
    assert settings.agent_mode is AgentMode.SINGLE
    assert settings.run_deadline_seconds == 60
    assert settings.llm_call_timeout_seconds == 30
    assert settings.max_auto_retries == 2
    assert settings.max_output_tokens == 1500
    assert settings.max_llm_calls_single == 3
    assert settings.max_llm_calls_multi == 8


def test_environment_variables_override_defaults(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("HOPS_AI_MODE", "real")
    monkeypatch.setenv("HOPS_AI_MAX_AUTO_RETRIES", "1")
    monkeypatch.setenv("HOPS_AI_RUN_DEADLINE_SECONDS", "45")

    settings = make_settings()

    assert settings.mode is RunMode.REAL
    assert settings.max_auto_retries == 1
    assert settings.run_deadline_seconds == 45


@pytest.mark.parametrize(
    ("agent_mode", "expected"),
    [("single", 3), ("multi", 8)],
)
def test_max_llm_calls_follows_agent_mode(
    monkeypatch: pytest.MonkeyPatch, agent_mode: str, expected: int
) -> None:
    monkeypatch.setenv("HOPS_AI_AGENT_MODE", agent_mode)

    assert make_settings().max_llm_calls == expected


@pytest.mark.parametrize(
    ("name", "value"),
    [
        ("HOPS_AI_MODE", "production"),
        ("HOPS_AI_AGENT_MODE", "triple"),
        ("HOPS_AI_RUN_DEADLINE_SECONDS", "0"),
        ("HOPS_AI_MAX_LLM_CALLS_SINGLE", "0"),
        ("HOPS_AI_MAX_AUTO_RETRIES", "-1"),
        ("HOPS_AI_MAX_OUTPUT_TOKENS", "0"),
    ],
)
def test_invalid_values_are_rejected(
    monkeypatch: pytest.MonkeyPatch, name: str, value: str
) -> None:
    monkeypatch.setenv(name, value)

    with pytest.raises(ValidationError):
        make_settings()


def test_call_timeout_longer_than_deadline_is_rejected(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("HOPS_AI_RUN_DEADLINE_SECONDS", "20")
    monkeypatch.setenv("HOPS_AI_LLM_CALL_TIMEOUT_SECONDS", "30")

    with pytest.raises(ValidationError):
        make_settings()


def test_env_example_names_are_known_settings() -> None:
    # extra="ignore"라서 이름에 오타가 있어도 조용히 무시된다. 예시 파일의 이름을 직접 검사한다.
    known = {f"HOPS_AI_{name.upper()}" for name in Settings.model_fields}

    unknown = set(read_env_example()) - known

    assert not unknown, f".env.example에 설정에 없는 이름이 있다: {sorted(unknown)}"


def test_env_example_values_equal_defaults(monkeypatch: pytest.MonkeyPatch) -> None:
    defaults = make_settings().model_dump()

    for name, value in read_env_example().items():
        monkeypatch.setenv(name, value)

    assert make_settings().model_dump() == defaults
