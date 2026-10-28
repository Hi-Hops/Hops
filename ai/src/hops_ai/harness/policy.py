"""역할별 도구 허용 목록. 기본은 모두 거부다.

여기서는 Python 쪽 1차 검사만 한다. 도구를 실제로 실행하기 전에 Spring이 권한을 다시 검사하며
최종 판단은 Spring이 한다. 거부된 도구는 다른 도구로 우회하지 않는다.
"""

from collections.abc import Collection, Mapping
from dataclasses import dataclass, field

from hops_ai.contracts.agent_1 import AgentRole
from hops_ai.harness.errors import PolicyDenied


@dataclass(frozen=True)
class ToolPolicy:
    allowed_by_role: Mapping[AgentRole, frozenset[str]] = field(default_factory=dict)

    @classmethod
    def of(cls, allowed: Mapping[AgentRole, Collection[str]]) -> "ToolPolicy":
        return cls({role: frozenset(tools) for role, tools in allowed.items()})

    def allowed_tools(self, role: AgentRole) -> list[str]:
        """agent-1 context.allowedTools에 담을 목록."""
        return sorted(self.allowed_by_role.get(role, frozenset()))

    def authorize(self, role: AgentRole, tool_name: str) -> None:
        if tool_name not in self.allowed_by_role.get(role, frozenset()):
            raise PolicyDenied(f"{role} 역할은 {tool_name} 도구를 쓸 수 없다")
