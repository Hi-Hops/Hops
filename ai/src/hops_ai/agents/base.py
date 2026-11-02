"""상세 입출력 합의 전 역할 인터페이스와 명시적인 미구현 기본 동작."""

from typing import TYPE_CHECKING, Protocol

from hops_ai.contracts.domain import AgentRole
from hops_ai.harness.errors import ComponentNotReady

if TYPE_CHECKING:
    from hops_ai.services.shared import SharedServices


class Agent(Protocol):
    role: AgentRole

    async def run(self, request: object, services: "SharedServices") -> object: ...


class PendingAgent:
    role: AgentRole

    async def run(self, request: object, services: "SharedServices") -> object:
        raise ComponentNotReady(self.role.value)
