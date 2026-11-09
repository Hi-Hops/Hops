"""프롬프트 계열별 모델 배분. 실제 OpenAI는 호출하지 않는다."""

import json
import re
from pathlib import Path

import httpx
import pytest
from pydantic import ValidationError

from hops_ai.services.llm.client import LLMMessage, LLMRequest
from hops_ai.services.llm.openai import OpenAIClient
from hops_ai.services.llm.routing import (
    PROMPT_FAMILIES,
    model_for,
    prompt_family,
    validate_role_models,
)
from hops_ai.settings import Settings
from tests.unit.test_openai_client import Reply, completed

SOURCE = Path(__file__).resolve().parents[2] / "src" / "hops_ai"


def request(prompt_version: str) -> LLMRequest:
    return LLMRequest(
        messages=[LLMMessage(role="user", content="질문")],
        response_model=Reply,
        max_output_tokens=100,
        prompt_version=prompt_version,
    )


@pytest.mark.parametrize(
    ("version", "family"),
    [
        ("knowledge.assessment.v5", "knowledge.assessment"),
        ("curator-draft-v4", "curator-draft"),
        ("supervisor.decision.v10", "supervisor.decision"),
        ("knowledge.guide-excerpt.v1", "knowledge.guide-excerpt"),
        ("inquiry-extraction-v10", "inquiry-extraction"),
    ],
)
def test_the_family_is_the_prompt_version_without_its_number(version, family) -> None:
    assert prompt_family(version) == family


def test_every_prompt_the_runtime_sends_belongs_to_a_known_family() -> None:
    # 새 프롬프트가 생기면 배분할 수 있도록 계열 목록에 함께 넣어야 한다
    found = {
        prompt_family(match)
        for path in SOURCE.rglob("*.py")
        for match in re.findall(
            r'(?:PROMPT_VERSION = |prompt_version=)"([^"]+)"', path.read_text("utf-8")
        )
    }
    assert found == PROMPT_FAMILIES


def test_a_family_without_an_assignment_uses_the_default_model() -> None:
    roles = {"knowledge.assessment": "big"}

    assert model_for("knowledge.assessment.v5", "small", roles) == ("big", "none")
    assert model_for("supervisor.decision.v10", "small", roles) == ("small", "none")


def test_a_model_may_name_its_reasoning_effort() -> None:
    # gpt-6.1-sol은 reasoning 강도 none을 거절한다. 모델마다 강도를 붙여 지정한다.
    roles = {"curator-draft": "gpt-6.1-sol:low"}

    assert model_for("curator-draft-v4", "gpt-6-luna", roles) == ("gpt-6.1-sol", "low")
    assert model_for("curator-link-v1", "gpt-6-luna:none", roles) == ("gpt-6-luna", "none")


@pytest.mark.parametrize(
    "roles",
    [
        {"knowledge.assesment": "big"},
        {"knowledge.assessment": " "},
        {"knowledge.assessment": "big:fast"},
        {"knowledge.assessment": ":low"},
    ],
)
def test_unknown_families_and_empty_models_are_rejected(roles) -> None:
    with pytest.raises(ValueError):
        validate_role_models(roles)
    with pytest.raises(ValidationError):
        Settings(_env_file=None, role_models=roles)


def test_settings_read_the_assignment_as_json(monkeypatch) -> None:
    monkeypatch.setenv("HOPS_AI_ROLE_MODELS", '{"curator-draft": "gpt-6.1-sol"}')

    assert Settings(_env_file=None).role_models == {"curator-draft": "gpt-6.1-sol"}
    monkeypatch.delenv("HOPS_AI_ROLE_MODELS")
    assert Settings(_env_file=None).role_models == {}


async def test_the_client_sends_each_call_to_its_familys_model() -> None:
    sent: list[tuple[str, str]] = []

    def handler(http_request: httpx.Request) -> httpx.Response:
        body = json.loads(http_request.content)
        sent.append((body["model"], body["reasoning"]["effort"]))
        return httpx.Response(200, json=completed())

    client = OpenAIClient(
        "sk-test",
        "gpt-6-luna",
        role_models={"knowledge.assessment": "gpt-6.1-terra", "curator-draft": "gpt-6.1-sol:low"},
        transport=httpx.MockTransport(handler),
    )

    await client.generate(request("knowledge.assessment.v5"))
    await client.generate(request("supervisor.decision.v10"))
    await client.generate(request("curator-draft-v4"))

    assert sent == [
        ("gpt-6.1-terra", "none"),
        ("gpt-6-luna", "none"),
        ("gpt-6.1-sol", "low"),
    ]


def test_the_client_rejects_an_unknown_family() -> None:
    with pytest.raises(ValueError):
        OpenAIClient("sk-test", "gpt-6-luna", role_models={"typo": "gpt-6.1-terra"})
