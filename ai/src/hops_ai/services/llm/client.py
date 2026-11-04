"""LLM 호출 인터페이스. 구현체(OpenAI, Fake)는 이 규약만 따른다.

- 구현체는 스스로 재시도하지 않는다. 재시도·시간 제한·호출 상한은 BudgetedLLMClient가 맡는다.
- 일시 오류(연결 장애·속도 제한)는 TransientError로, 형식 복구 후에도 응답을
  response_model로 만들 수 없으면 OutputRejected로 알린다.
"""

from collections.abc import Sequence
from dataclasses import dataclass
from typing import Any, Literal, Protocol, runtime_checkable

from pydantic import BaseModel


@dataclass(frozen=True)
class LLMMessage:
    role: Literal["system", "user", "assistant"]
    content: str


@dataclass(frozen=True)
class LLMRequest[T: BaseModel]:
    messages: Sequence[LLMMessage]
    response_model: type[T]
    max_output_tokens: int
    prompt_version: str  # 결과 meta.promptVersion에 기록한다
    max_input_tokens: int | None = None
    # 지정하면 구현체가 모델 출력 형식을 response_model의 기본 스키마 대신 이 스키마로 제한한다.
    # 응답 해석과 검증은 그대로 response_model로 한다(예: 이번 호출에서 선택 가능한 값만 열거).
    response_schema: dict[str, Any] | None = None


@dataclass(frozen=True)
class LLMUsage:
    input_tokens: int
    output_tokens: int


@dataclass(frozen=True)
class LLMResponse[T: BaseModel]:
    parsed: T
    usage: LLMUsage
    model_id: str


@runtime_checkable
class LLMClient(Protocol):
    async def generate[T: BaseModel](self, request: LLMRequest[T]) -> LLMResponse[T]: ...
