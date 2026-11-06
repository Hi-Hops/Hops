from pathlib import Path

import pytest
from pydantic import ValidationError

from hops_ai.harness.errors import ComponentNotReady
from hops_ai.services.retrieval.spring_source import SpringKnowledgeSource
from hops_ai.services.tickets import TicketHTTPClient
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


# Compose는 값이 없으면 SPRING_SERVICE_TOKEN을 빈 문자열로 넣는다. 가상 값만 사용한다.
@pytest.mark.parametrize("blank", ["", "   "])
def test_blank_spring_settings_are_unset_not_invalid(monkeypatch, make_settings, blank) -> None:
    monkeypatch.setenv("SPRING_SERVICE_TOKEN", blank)
    monkeypatch.setenv("HOPS_AI_SPRING_BASE_URL", blank)
    settings = make_settings()
    assert settings.spring_service_token is None
    assert settings.spring_base_url is None
    assert settings.mode is RunMode.SCAFFOLD


@pytest.mark.parametrize("token", ["short", "*" * 43, "A" * 42])
def test_non_blank_invalid_spring_token_is_still_rejected(make_settings, token) -> None:
    with pytest.raises(ValidationError) as error:
        make_settings(spring_service_token=token)
    assert token not in str(error.value)


def test_valid_spring_settings_are_kept(make_settings) -> None:
    settings = make_settings(
        spring_base_url="http://backend:8080", spring_service_token=TEST_SERVICE_TOKEN
    )
    assert settings.spring_base_url == "http://backend:8080"
    assert settings.spring_service_token.get_secret_value() == TEST_SERVICE_TOKEN


@pytest.mark.parametrize(
    "build",
    [
        lambda settings: TicketHTTPClient(settings),
        lambda settings: SpringKnowledgeSource(settings),
    ],
)
def test_missing_spring_settings_name_the_variables_to_set(make_settings, build) -> None:
    with pytest.raises(ComponentNotReady) as error:
        build(make_settings())
    message = str(error.value)
    assert "HOPS_AI_SPRING_BASE_URL" in message
    assert "SPRING_SERVICE_TOKEN" in message
    assert "구현" not in message


def test_component_not_ready_without_missing_keeps_the_original_message() -> None:
    assert str(ComponentNotReady("synthetic")) == "synthetic: 아직 구현·연결되지 않았다"
