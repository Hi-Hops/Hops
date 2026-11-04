from pathlib import Path

import pytest
from pydantic import ValidationError

from hops_ai.settings import KnowledgeSourceKind, RunMode, Settings


def test_default_mode_never_enables_fake_business_execution(make_settings) -> None:
    assert make_settings().mode is RunMode.SCAFFOLD


def test_knowledge_source_is_disconnected_by_default(make_settings) -> None:
    assert make_settings().knowledge_source is KnowledgeSourceKind.NONE


def test_knowledge_source_fixture_requires_explicit_opt_in(monkeypatch, make_settings) -> None:
    monkeypatch.setenv("HOPS_AI_KNOWLEDGE_SOURCE", "fixture")
    assert make_settings().knowledge_source is KnowledgeSourceKind.FIXTURE


def test_unknown_knowledge_source_is_rejected(make_settings) -> None:
    with pytest.raises(ValidationError):
        make_settings(knowledge_source="pgvector")


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


def test_env_example_contains_only_current_defaults(monkeypatch, make_settings) -> None:
    known = {f"HOPS_AI_{name.upper()}" for name in Settings.model_fields}
    defaults = make_settings().model_dump()
    example = Path(__file__).resolve().parents[2] / ".env.example"
    for line in example.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            name, value = line.split("=", 1)
            assert name in known
            monkeypatch.setenv(name, value)
    assert make_settings().model_dump() == defaults


# 가상 값만 사용한다. 실제 토큰·DB 접속 정보를 테스트에 넣지 않는다.
TEST_SERVICE_TOKEN = "A" * 43
TEST_RUNTIME_DSN = "dbname=hops_ai_runtime user=test_user"


def test_internal_profile_requires_token_and_dedicated_db(make_settings) -> None:
    with pytest.raises(ValidationError):
        make_settings(internal_api_enabled=True)
    valid = make_settings(
        internal_api_enabled=True,
        runtime_dsn=TEST_RUNTIME_DSN,
        ai_service_token=TEST_SERVICE_TOKEN,
    )
    assert valid.internal_api_enabled
    assert TEST_SERVICE_TOKEN not in repr(valid)
    assert TEST_RUNTIME_DSN not in repr(valid)


@pytest.mark.parametrize("token", ["", "short", "*" * 43, "가" * 43, "A" * 42])
def test_internal_profile_rejects_invalid_service_tokens(make_settings, token) -> None:
    with pytest.raises(ValidationError) as error:
        make_settings(
            internal_api_enabled=True, runtime_dsn=TEST_RUNTIME_DSN, ai_service_token=token
        )
    if token:
        assert token not in str(error.value)


@pytest.mark.parametrize("dsn", ["", "dbname=hops", "not valid connection string"])
def test_internal_profile_rejects_wrong_runtime_database(make_settings, dsn) -> None:
    with pytest.raises(ValidationError) as error:
        make_settings(
            internal_api_enabled=True, runtime_dsn=dsn, ai_service_token=TEST_SERVICE_TOKEN
        )
    if dsn:
        assert dsn not in str(error.value)


def test_service_token_reads_unprefixed_environment_name(monkeypatch, make_settings) -> None:
    monkeypatch.setenv("HOPS_AI_INTERNAL_API_ENABLED", "true")
    monkeypatch.setenv("HOPS_AI_RUNTIME_DSN", TEST_RUNTIME_DSN)
    monkeypatch.setenv("AI_SERVICE_TOKEN", TEST_SERVICE_TOKEN)
    assert make_settings().ai_service_token.get_secret_value() == TEST_SERVICE_TOKEN
