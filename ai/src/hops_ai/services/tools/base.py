"""읽기 전용 도구 인터페이스. 실제 실행은 Spring Read Tool Gateway가 권한을 검사한 뒤 한다.

- 도구는 읽기 전용이며 실제 시스템을 변경하지 않는다.
- DENIED는 최종이다. 거부된 도구를 다른 도구로 우회하지 않는다.
- 조회 실패를 정상 상태로 바꾸지 않는다. OK가 아니면 근거(Evidence)도 없다.
"""

from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any, Literal, Protocol, runtime_checkable

from hops_ai.contracts.agent_1 import AgentRole, Evidence

ToolStatus = Literal["OK", "DENIED", "TIMEOUT", "ERROR"]


@dataclass(frozen=True)
class ToolRequest:
    tool_name: str
    arguments: Mapping[str, Any]


@dataclass(frozen=True)
class ToolResult:
    status: ToolStatus
    tool_name: str
    data: Mapping[str, Any] | None
    evidence: Evidence | None  # OK이면 TOOL 근거(조회 시각 포함), 아니면 None
    message: str | None  # 사용자에게 보여도 되는 요약. 내부 오류 원문은 담지 않는다

    def __post_init__(self) -> None:
        if self.status == "OK":
            if self.data is None or self.evidence is None or self.evidence.kind != "TOOL":
                raise ValueError("OK 결과는 data와 TOOL 근거가 필요하다")
        elif self.data is not None or self.evidence is not None:
            raise ValueError("OK가 아닌 결과는 data와 근거를 가질 수 없다")

    @classmethod
    def ok(cls, tool_name: str, data: Mapping[str, Any], evidence: Evidence) -> "ToolResult":
        return cls("OK", tool_name, data, evidence, None)

    @classmethod
    def denied(cls, tool_name: str, message: str) -> "ToolResult":
        return cls("DENIED", tool_name, None, None, message)

    @classmethod
    def timeout(cls, tool_name: str, message: str) -> "ToolResult":
        return cls("TIMEOUT", tool_name, None, None, message)

    @classmethod
    def error(cls, tool_name: str, message: str) -> "ToolResult":
        return cls("ERROR", tool_name, None, None, message)


@runtime_checkable
class ToolGateway(Protocol):
    async def call(self, role: AgentRole, request: ToolRequest) -> ToolResult: ...
