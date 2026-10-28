"""역할 에이전트와 단일 에이전트를 모아 두고, 설정에 맞는 분석 실행기를 고른다.

설정 HOPS_AI_MODE로 가짜/실제 구현을, HOPS_AI_AGENT_MODE로 단일/멀티 구성을 고른다.
호출하는 쪽 코드는 어느 쪽인지 모른다.
"""

from collections.abc import Mapping
from dataclasses import dataclass

from hops_ai.agents.base import Agent, Analyzer
from hops_ai.contracts.agent_1 import AgentRole
from hops_ai.fakes.agents import (
    FakeBaselineAgent,
    FakeDiagnosisAgent,
    FakeIntakeAgent,
    FakeKnowledgeAgent,
    FakeTicketAgent,
)
from hops_ai.harness.events import RunEvents
from hops_ai.settings import AgentMode, RunMode, Settings
from hops_ai.supervisor.graph import Supervisor


@dataclass(frozen=True)
class AgentRegistry:
    roles: Mapping[AgentRole, Agent]
    baseline: Analyzer


def build_fake_registry() -> AgentRegistry:
    return AgentRegistry(
        roles={
            "INTAKE": FakeIntakeAgent(),
            "KNOWLEDGE": FakeKnowledgeAgent(),
            "DIAGNOSIS": FakeDiagnosisAgent(),
            "TICKET": FakeTicketAgent(),
        },
        baseline=FakeBaselineAgent(),
    )


def build_registry(settings: Settings) -> AgentRegistry:
    if settings.mode is RunMode.FAKE:
        return build_fake_registry()
    # 조용히 가짜로 대체하지 않는다. 실제 에이전트는 모델·검색·도구를 연결하는 후속 단계에서 만든다.
    raise NotImplementedError("real 모드는 실제 에이전트를 연결하는 후속 단계에서 지원한다")


def select_analyzer(
    settings: Settings, registry: AgentRegistry, *, events: RunEvents | None = None
) -> Analyzer:
    """단일 구성이면 baseline, 멀티 구성이면 Supervisor. 이벤트는 실행마다 달라 인자로 받는다."""
    if settings.agent_mode is AgentMode.SINGLE:
        return registry.baseline
    return Supervisor(registry.roles, events=events)
