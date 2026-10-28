"""도구 호출 앞단의 검사. Python 쪽 1차 검사이며 최종 권한 판단은 Spring이 한다.

순서: 등록된 도구인가 → 이 역할에 허용됐나 → 인자가 규칙에 맞나 → 실제 게이트웨이 호출.
앞 검사를 통과하지 못하면 실제 게이트웨이를 부르지 않는다.
"""

from hops_ai.contracts.agent_1 import AgentRole
from hops_ai.harness.errors import PolicyDenied
from hops_ai.harness.policy import ToolPolicy
from hops_ai.services.tools.base import ToolGateway, ToolRequest, ToolResult
from hops_ai.services.tools.registry import ToolRegistry


class GuardedToolGateway:
    def __init__(self, inner: ToolGateway, registry: ToolRegistry, policy: ToolPolicy) -> None:
        self._inner = inner
        self._registry = registry
        self._policy = policy

    async def call(self, role: AgentRole, request: ToolRequest) -> ToolResult:
        spec = self._registry.get(request.tool_name)
        if spec is None:
            return ToolResult.denied(request.tool_name, "등록되지 않은 도구다")
        try:
            self._policy.authorize(role, request.tool_name)
        except PolicyDenied:
            return ToolResult.denied(request.tool_name, "이 역할에 허용되지 않은 도구다")
        problem = spec.argument_problem(request.arguments)
        if problem is not None:
            return ToolResult.error(request.tool_name, "도구 인자가 올바르지 않다")
        return await self._inner.call(role, request)
