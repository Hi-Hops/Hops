"""출력 가드레일 인터페이스. 스키마·근거 ID·부서 코드 검증은 후속 단계에서 구현한다."""

from typing import Protocol, runtime_checkable

from hops_ai.contracts.agent_1 import AgentRequest, AgentResult


@runtime_checkable
class OutputGuard(Protocol):
    def check(self, request: AgentRequest, result: AgentResult) -> AgentResult:
        """검증을 통과한 결과를 돌려주거나, 통과하지 못하면 OutputRejected를 낸다."""
        ...


class NoopOutputGuard:
    """아무것도 검사하지 않는다. Fake 모드와 인터페이스 확인용이며 실제 모델에는 쓰지 않는다."""

    def check(self, request: AgentRequest, result: AgentResult) -> AgentResult:
        return result
