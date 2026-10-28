"""실행 예산 안에서 LLM을 호출한다. 에이전트는 이 클라이언트만 쓰며 구현체를 알 필요가 없다.

예산(마감·호출 상한)과 재시도는 하네스 규칙을 그대로 적용한다. 호출당 출력 토큰은 예산의 상한으로
줄여서 요청한다.
"""

import asyncio
import random
from collections.abc import Awaitable, Callable
from dataclasses import replace

from pydantic import BaseModel

from hops_ai.harness.budget import RunBudget
from hops_ai.harness.retry import call_with_retry
from hops_ai.services.llm.client import LLMClient, LLMRequest, LLMResponse
from hops_ai.services.llm.usage import UsageRecorder


class BudgetedLLMClient:
    def __init__(
        self,
        inner: LLMClient,
        budget: RunBudget,
        usage: UsageRecorder,
        *,
        sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
        rng: Callable[[], float] = random.random,
    ) -> None:
        self._inner = inner
        self._budget = budget
        self._usage = usage
        self._sleep = sleep
        self._rng = rng

    async def generate[T: BaseModel](self, request: LLMRequest[T]) -> LLMResponse[T]:
        limited = replace(
            request,
            max_output_tokens=min(request.max_output_tokens, self._budget.max_output_tokens),
        )
        response = await call_with_retry(
            lambda: self._inner.generate(limited),
            self._budget,
            sleep=self._sleep,
            rng=self._rng,
        )
        self._usage.record(
            model_id=response.model_id,
            prompt_version=request.prompt_version,
            usage=response.usage,
        )
        return response
