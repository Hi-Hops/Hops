"""Ticket 역할 구성의 명시성. 제안 전용 구현과 실행 구현을 호출 측이 고른다."""

import pytest

from hops_ai.agents.registry import build_registry
from hops_ai.agents.ticket import ModelTicketAgent
from hops_ai.agents.ticket.executing import ExecutingTicketAgent
from hops_ai.contracts.domain import AgentRole
from hops_ai.services.runs.supervisor import SupervisorPipeline
from tests.doubles.llm import FakeLLMClient


def test_default_runtime_registry_only_proposes_tickets() -> None:
    assert type(build_registry()[AgentRole.TICKET]) is ModelTicketAgent


def test_runtime_registry_executes_tickets_only_when_asked() -> None:
    agent = build_registry(execute_tickets=True)[AgentRole.TICKET]

    assert type(agent) is ExecutingTicketAgent


def test_executing_agent_builds_its_proposer_once(monkeypatch) -> None:
    from hops_ai.agents.ticket import executing

    created = []

    class CountingProposer(ModelTicketAgent):
        def __init__(self) -> None:
            created.append(self)

    monkeypatch.setattr(executing, "ModelTicketAgent", CountingProposer)

    ExecutingTicketAgent()

    assert len(created) == 1


def test_pipeline_does_not_silently_replace_an_injected_ticket_role() -> None:
    agents = dict(build_registry())

    with pytest.raises(ValueError, match="ticket_client"):
        SupervisorPipeline(FakeLLMClient(), agents, ticket_client=object())  # type: ignore[arg-type]
