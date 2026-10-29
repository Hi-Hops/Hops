"""입력 가드레일 인터페이스. 개인정보 마스킹·인젝션 탐지는 후속 단계에서 구현한다."""

from collections.abc import Sequence
from typing import Protocol, runtime_checkable

from hops_ai.contracts.agent_1 import Message


@runtime_checkable
class InputGuard(Protocol):
    def check(self, messages: Sequence[Message]) -> list[Message]:
        """정제한 메시지를 돌려주거나, 허용할 수 없으면 InputRejected를 낸다."""
        ...


class NoopInputGuard:
    """아무것도 검사하지 않는다. Fake 모드와 인터페이스 확인용이며 실제 모델에는 쓰지 않는다."""

    def check(self, messages: Sequence[Message]) -> list[Message]:
        return list(messages)
