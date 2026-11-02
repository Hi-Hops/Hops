"""한 번의 실행(runId)이 쓸 수 있는 시간과 모델 호출 횟수.

- 마감 시각은 역할이 바뀌어도 초기화하지 않는다. 모든 역할이 같은 예산 객체를 공유한다.
- 모델 호출 상한에는 자동 재시도도 포함한다.
- 마감 확인이 호출 상한 확인보다 먼저다.
"""

import time
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Protocol

from hops_ai.harness.errors import DeadlineExceeded, LlmCallLimitExceeded
from hops_ai.settings import Settings


class Clock(Protocol):
    def monotonic(self) -> float: ...

    def now(self) -> datetime: ...


class SystemClock:
    def monotonic(self) -> float:
        return time.monotonic()

    def now(self) -> datetime:
        return datetime.now(UTC)


@dataclass
class RunBudget:
    clock: Clock
    deadline: float  # clock.monotonic() 기준 마감 시각
    max_llm_calls: int
    max_auto_retries: int
    llm_call_timeout: float
    max_output_tokens: int
    llm_calls_used: int = 0

    @classmethod
    def start(
        cls,
        settings: Settings,
        *,
        deadline_at: datetime | None = None,
        clock: Clock | None = None,
    ) -> "RunBudget":
        """설정의 실행 제한과 Spring이 알려 준 마감 시각 중 더 이른 쪽을 마감으로 쓴다."""
        clock = clock or SystemClock()
        window = float(settings.run_deadline_seconds)
        if deadline_at is not None:
            window = min(window, (deadline_at - clock.now()).total_seconds())
        return cls(
            clock=clock,
            deadline=clock.monotonic() + window,
            max_llm_calls=settings.max_llm_calls,
            max_auto_retries=settings.max_auto_retries,
            llm_call_timeout=float(settings.llm_call_timeout_seconds),
            max_output_tokens=settings.max_output_tokens,
        )

    def remaining_seconds(self) -> float:
        return max(0.0, self.deadline - self.clock.monotonic())

    def effective_deadline_at(self) -> datetime:
        """현재 시각 기준으로 환산한 마감 시각(UTC)."""
        return self.clock.now() + timedelta(seconds=self.remaining_seconds())

    def check_deadline(self) -> None:
        if self.remaining_seconds() <= 0:
            raise DeadlineExceeded

    def start_llm_call(self) -> float:
        """모델 호출 1회를 예약하고 이번 호출에 쓸 제한 시간(초)을 돌려준다.

        마감이 지났거나 호출 상한을 다 썼으면 호출을 시작하지 않고 예외를 낸다.
        """
        self.check_deadline()
        if self.llm_calls_used >= self.max_llm_calls:
            raise LlmCallLimitExceeded(f"허용 {self.max_llm_calls}회를 모두 사용했다")
        self.llm_calls_used += 1
        return min(self.llm_call_timeout, self.remaining_seconds())
