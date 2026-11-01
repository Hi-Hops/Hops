"""OpenAI 어댑터 자리. 모델·프롬프트·인증·예산 검토 전에는 외부 호출하지 않는다."""

from pydantic import BaseModel

from hops_ai.harness.errors import ComponentNotReady
from hops_ai.services.llm.client import LLMRequest, LLMResponse


class OpenAIClient:
    async def generate[T: BaseModel](self, request: LLMRequest[T]) -> LLMResponse[T]:
        raise ComponentNotReady("OpenAI client")
