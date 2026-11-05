"""Supervisor가 호출하는 전문 역할의 입력·반환 타입과 미구현 기본 동작."""

from typing import Protocol

from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.roles import RoleResult
from hops_ai.contracts.state import RoleRequest, RoleResponse
from hops_ai.harness.errors import ComponentNotReady
from hops_ai.services.shared import SharedServices


class Agent(Protocol):
    """호출 ID를 유지한 역할 결과와 필요한 신뢰된 어댑터 자료를 반환한다."""

    role: AgentRole

    async def run(
        self, request: RoleRequest, services: SharedServices
    ) -> RoleResult | RoleResponse: ...


class PendingAgent:
    """아직 연결하지 않은 역할을 명시적으로 거부한다. 성공 결과를 자동 생성하지 않는다."""

    def __init__(self, role: AgentRole) -> None:
        self.role = role

    async def run(
        self, request: RoleRequest, services: SharedServices
    ) -> RoleResult | RoleResponse:
        raise ComponentNotReady(self.role.value)


def pending_agents() -> dict[AgentRole, Agent]:
    """모든 역할을 연결 전 자리로 둔 조합. 구현을 주입하지 않은 Supervisor의 기본값이다."""
    return {role: PendingAgent(role) for role in AgentRole}
