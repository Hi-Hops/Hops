"""공통 예산과 재시도를 적용한 LLM 호출. 업무 State나 특정 역할에 의존하지 않는다."""

import asyncio
import random
from collections.abc import Awaitable, Callable
from dataclasses import replace

from pydantic import BaseModel

from hops_ai.harness.budget import RunBudget
from hops_ai.harness.errors import BudgetExceeded, OutputRejected
from hops_ai.harness.retry import call_with_retry
from hops_ai.services.llm.client import LLMClient, LLMRequest, LLMResponse, LLMUsage
from hops_ai.services.llm.usage import UsageRecorder


class BudgetedLLMClient:
    """원시 LLM을 감싸 호출·토큰 한도와 공통 재시도 정책을 적용한다.

    같은 wrapper의 동시 요청은 직렬화하여 남은 출력 토큰을 중복 사용하지 않는다.
    공급자가 보고한 사용량만 기록하며 실패한 원격 호출의 미보고 과금은 추정하지 않는다.
    """

    def __init__(
        self,
        inner: LLMClient,
        budget: RunBudget,
        usage: UsageRecorder,
        *,
        sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
        rng: Callable[[], float] = random.random,
        ensure_active: Callable[[], None] | None = None,
    ) -> None:
        self._inner = inner
        self._budget = budget
        self._usage = usage
        self._sleep = sleep
        self._rng = rng
        self._check_execution = ensure_active
        self._lock = asyncio.Lock()

    def _ensure_active(self) -> None:
        """실행기 수명·취소 검사와 공통 예산 검사를 함께 적용한다."""
        if self._check_execution is not None:
            self._check_execution()
        self._budget.check_deadline()
        self._budget.check_limits()

    async def generate[T: BaseModel](self, request: LLMRequest[T]) -> LLMResponse[T]:
        """응답을 얻고 보고 토큰을 누적한다. 일시 오류 재시도는 하네스에 위임한다.

        출력 요청은 호출당 서버 상한과 실행 전체 잔여량으로 줄인다. 응답의 형식·사용량을
        검사하고 초과 사용량은 기록한 뒤 결과를 거부한다. 입력 토큰의 정확한 사전 계산은
        공급자 어댑터가 담당하며, 이 wrapper는 응답 뒤 전체 상한을 다시 검사한다.
        """
        async with self._lock:
            self._ensure_active()
            input_limit = request.max_input_tokens
            if self._budget.max_input_tokens is not None:
                remaining = self._budget.max_input_tokens - self._budget.input_tokens_used
                input_limit = remaining if input_limit is None else min(remaining, input_limit)
            limited = replace(
                request,
                max_input_tokens=input_limit,
                max_output_tokens=self._budget.limit_output_tokens(request.max_output_tokens),
            )

            async def invoke() -> LLMResponse[T]:
                """응답 직후 보고 사용량을 보존하고 이후 활성 검사에서 늦은 결과를 차단한다."""
                response = await self._inner.generate(limited)
                if not isinstance(response, LLMResponse) or not isinstance(
                    response.usage, LLMUsage
                ):
                    raise OutputRejected("모델 응답 형식이 올바르지 않다")
                self._budget.record_tokens(
                    input_tokens=response.usage.input_tokens,
                    output_tokens=response.usage.output_tokens,
                )
                self._usage.record(
                    model_id=response.model_id,
                    prompt_version=request.prompt_version,
                    usage=response.usage,
                )
                if response.usage.output_tokens > limited.max_output_tokens:
                    raise BudgetExceeded("모델이 호출당 출력 한도를 넘었다")
                if not isinstance(response.parsed, request.response_model):
                    raise OutputRejected("모델 응답이 요청한 구조화 모델과 다르다")
                return response

            return await call_with_retry(
                invoke,
                self._budget,
                sleep=self._sleep,
                rng=self._rng,
                ensure_active=self._ensure_active,
            )
