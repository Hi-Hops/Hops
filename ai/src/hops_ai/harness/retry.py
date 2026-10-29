"""모델 호출의 일시 오류 자동 재시도.

재시도는 이 하네스에서만 한다. 모델 SDK의 자체 재시도는 꺼 두어야 한다(층마다 재시도하면
호출 수가 곱해진다). 재시도도 실행 예산의 호출 상한·마감에 포함한다.
"""

import asyncio
import random
from collections.abc import Awaitable, Callable

from hops_ai.harness.budget import RunBudget
from hops_ai.harness.errors import CallTimeoutError, TransientError

DEFAULT_BASE_DELAY = 0.5
DEFAULT_MAX_DELAY = 8.0


def backoff_delay(
    retry_index: int,
    *,
    base: float = DEFAULT_BASE_DELAY,
    cap: float = DEFAULT_MAX_DELAY,
    rng: Callable[[], float] = random.random,
) -> float:
    """지수 백오프에 전체 jitter를 적용한 대기 시간(초). 0 이상 min(cap, base*2^n) 이하다."""
    return rng() * min(cap, base * 2**retry_index)


async def call_with_retry[T](
    operation: Callable[[], Awaitable[T]],
    budget: RunBudget,
    *,
    sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
    rng: Callable[[], float] = random.random,
    base_delay: float = DEFAULT_BASE_DELAY,
    max_delay: float = DEFAULT_MAX_DELAY,
) -> T:
    """operation을 예산 안에서 실행하고 일시 오류(TransientError)와 시간 초과만 재시도한다.

    - 시도마다 모델 호출 1회를 예약한다. 상한이나 마감을 넘으면 새 호출을 시작하지 않는다.
    - 호출 시간 제한은 이 함수가 강제한다. 초과하면 일시 오류로 보고 재시도 대상이 된다.
    - 서버가 알려 준 대기 시간(retry_after)이 있으면 그만큼 기다린다.
    - 남은 시간이 대기 시간보다 짧으면 기다리지 않고 마지막 오류를 낸다.
    - 일시 오류가 아닌 예외와 취소(CancelledError)는 그대로 전달하며 재시도하지 않는다.
    """
    retries = 0
    while True:
        timeout = budget.start_llm_call()
        try:
            async with asyncio.timeout(timeout):
                return await operation()
        except TimeoutError:
            failure: TransientError = CallTimeoutError()
        except TransientError as error:
            failure = error

        budget.check_deadline()
        if retries >= budget.max_auto_retries:
            raise failure
        if failure.retry_after is not None:
            delay = failure.retry_after
        else:
            delay = backoff_delay(retries, base=base_delay, cap=max_delay, rng=rng)
        if delay >= budget.remaining_seconds():
            raise failure
        await sleep(delay)
        retries += 1
