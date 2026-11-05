"""평가 실행 전체의 모델 호출 상한. Spring의 실행당 한도(8회)와는 별개다.

상한은 호출 **전에** 확인하고 소모한다. 그래서 어떤 역할이 예외를 삼켜도 실제 원격 호출은
상한을 넘지 않는다. 실패한 호출도 과금될 수 있으므로 소모로 센다.
"""

from pydantic import BaseModel

from hops_ai.services.llm.client import LLMClient, LLMRequest, LLMResponse


class CallCapExceeded(Exception):
    """평가 전체 호출 상한에 도달해 더 호출하지 않는다."""


class CallCap:
    def __init__(self, max_calls: int) -> None:
        if max_calls < 1:
            raise ValueError("max_calls는 1 이상이어야 한다")
        self.max_calls = max_calls
        self.used = 0
        self.exceeded = False
        # 호출에 실린 프롬프트 버전. 실행 기록에 남겨 같은 조건끼리만 비교하게 한다.
        self.prompt_versions: set[str] = set()

    def reserve(self) -> None:
        if self.used >= self.max_calls:
            self.exceeded = True
            raise CallCapExceeded(f"모델 호출 상한 {self.max_calls}회에 도달했다")
        self.used += 1


class CappedLLM:
    """모델 클라이언트를 감싸 전체 호출 상한을 적용한다."""

    def __init__(self, inner: LLMClient, cap: CallCap) -> None:
        self._inner = inner
        self._cap = cap

    async def generate[T: BaseModel](self, request: LLMRequest[T]) -> LLMResponse[T]:
        self._cap.reserve()
        self._cap.prompt_versions.add(request.prompt_version)
        return await self._inner.generate(request)
