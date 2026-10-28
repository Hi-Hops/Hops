"""가짜 도구 게이트웨이. 정해 둔 조회 결과를 돌려준다. 실제 시스템을 조회하지 않는 가상 데이터다."""

from collections.abc import Callable, Mapping
from typing import Any

from hops_ai.contracts.agent_1 import AgentRole, Evidence
from hops_ai.services.tools.base import ToolRequest, ToolResult

Response = ToolResult | Callable[[ToolRequest], ToolResult]


def fake_tool_ok(
    tool_name: str,
    data: Mapping[str, Any],
    *,
    result_id: str,
    observed_at: str,
) -> ToolResult:
    """조회 시각이 있는 TOOL 근거가 붙은 정상 결과."""
    evidence = Evidence(
        id=f"tool-{result_id}",
        kind="TOOL",
        sourceId=result_id,
        sourceVersion="fake-snapshot-1",
        summary=f"가상 {tool_name} 조회 결과",
        observedAt=observed_at,
    )
    return ToolResult.ok(tool_name, data, evidence)


class FakeToolGateway:
    def __init__(self, responses: Mapping[str, Response] | None = None) -> None:
        self._responses = dict(responses or {})
        self.calls: list[tuple[AgentRole, ToolRequest]] = []

    async def call(self, role: AgentRole, request: ToolRequest) -> ToolResult:
        self.calls.append((role, request))
        response = self._responses.get(request.tool_name)
        if response is None:
            return ToolResult.error(request.tool_name, "Fake에 준비된 응답이 없다")
        if callable(response):
            return response(request)
        return response
