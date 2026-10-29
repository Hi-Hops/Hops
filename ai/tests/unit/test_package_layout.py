import importlib

import pytest

PACKAGES = [
    "hops_ai",
    "hops_ai.api",
    "hops_ai.api.routes",
    "hops_ai.contracts",
    "hops_ai.harness",
    "hops_ai.harness.guardrails",
    "hops_ai.supervisor",
    "hops_ai.agents",
    "hops_ai.agents.baseline",
    "hops_ai.agents.intake",
    "hops_ai.agents.knowledge",
    "hops_ai.agents.diagnosis",
    "hops_ai.agents.ticket",
    "hops_ai.services",
    "hops_ai.services.llm",
    "hops_ai.services.retrieval",
    "hops_ai.services.tools",
    "hops_ai.fakes",
]


@pytest.mark.parametrize("name", PACKAGES)
def test_package_can_be_imported(name: str) -> None:
    module = importlib.import_module(name)

    assert module.__doc__, f"{name}에 역할을 설명하는 docstring이 필요하다"
