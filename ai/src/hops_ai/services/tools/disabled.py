"""연결된 읽기 도구가 없을 때 쓰는 게이트웨이. 모든 호출을 거부하고 성공을 만들지 않는다."""

from hops_ai.contracts.domain import AgentRole
from hops_ai.services.tools.base import ToolRequest, ToolResult


class DisabledToolGateway:
    async def call(self, role: AgentRole, request: ToolRequest) -> ToolResult:
        return ToolResult.denied(request.tool_name, "조회 도구가 연결되지 않았습니다")
