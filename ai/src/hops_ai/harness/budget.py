"""한 번의 실행(runId)이 쓸 수 있는 시간과 누적 사용량.

- 마감 시각은 역할이 바뀌어도 초기화하지 않는다. 모든 역할이 같은 예산 객체를 공유한다.
- 모델 호출 상한에는 자동 재시도도 포함한다.
- 마감 확인이 호출 상한 확인보다 먼저다.
"""

import time
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Protocol

from hops_ai.harness.errors import (
    BudgetExceeded,
    DeadlineExceeded,
    LlmCallLimitExceeded,
    OutputRejected,
)
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
    """모든 역할이 공유하는 실행 제한과 실제로 예약·보고된 사용량.

    max_output_tokens는 서버의 호출당 출력 상한이다. max_total_output_tokens는
    실행 전체의 출력 상한이며, 나머지 optional 한도도 None이면 적용하지 않는다.
    실패한 모델 시도도 llm_calls_used에 포함한다. 토큰은 공급자가 보고한 값만 센다.
    """

    clock: Clock
    deadline: float  # clock.monotonic() 기준 마감 시각
    max_llm_calls: int
    max_auto_retries: int
    llm_call_timeout: float
    max_output_tokens: int
    llm_calls_used: int = 0
    max_input_tokens: int | None = None
    max_total_output_tokens: int | None = None
    max_tool_calls: int | None = None
    max_run_retries: int | None = None
    input_tokens_used: int = 0
    output_tokens_used: int = 0
    tool_calls_used: int = 0
    retries_used: int = 0

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
        """서버와 요청 중 더 이른 마감 시각이 지났으면 실행을 중단한다."""
        if self.remaining_seconds() <= 0:
            raise DeadlineExceeded

    def constrain(
        self,
        *,
        max_model_calls: int,
        max_input_tokens: int,
        max_total_output_tokens: int,
        max_tool_calls: int,
        max_run_retries: int,
        deadline_at: datetime,
    ) -> None:
        """요청의 누적 한도로 기존 서버 예산을 좁히고 사용량은 보존한다.

        Args:
            max_model_calls: 최초 호출과 재시도를 합친 모델 시도 상한.
            max_input_tokens: 공급자가 보고한 누적 입력 토큰 상한.
            max_total_output_tokens: 누적 출력 토큰 상한. 호출당 상한과 별개다.
            max_tool_calls: 실행 전체의 도구 호출 상한.
            max_run_retries: 여러 모델 호출에 걸친 총 재시도 상한. 0도 허용한다.
            deadline_at: 시간대가 있는 요청 마감. 서버 마감을 연장하지 않는다.

        Raises:
            OutputRejected: 정수가 아닌 상한이나 잘못된 마감 시각.
            BudgetExceeded: 새 상한보다 이미 사용한 누적 사용량이 많은 경우.

        만료된 기한은 그대로 유지하고 실제 실행 시 check_deadline에서 거부한다.
        """
        limits = (
            ("max_llm_calls", max_model_calls, 1),
            ("max_input_tokens", max_input_tokens, 1),
            ("max_total_output_tokens", max_total_output_tokens, 1),
            ("max_tool_calls", max_tool_calls, 1),
            ("max_run_retries", max_run_retries, 0),
        )
        if any(type(value) is not int or value < minimum for _, value, minimum in limits):
            raise OutputRejected("실행 상한의 형식이 올바르지 않다")
        if not isinstance(deadline_at, datetime) or deadline_at.utcoffset() is None:
            raise OutputRejected("실행 마감에는 시간대가 필요하다")

        for name, value, _ in limits:
            current = getattr(self, name)
            setattr(self, name, value if current is None else min(current, value))
        request_window = (deadline_at - self.clock.now()).total_seconds()
        self.deadline = min(self.deadline, self.clock.monotonic() + request_window)
        self.check_limits()

    def check_limits(self) -> None:
        """누적 카운터 형식과 상한을 확인한다. 사용량을 초기화하거나 증가시키지 않는다."""
        usage = (
            (self.llm_calls_used, self.max_llm_calls),
            (self.input_tokens_used, self.max_input_tokens),
            (self.output_tokens_used, self.max_total_output_tokens),
            (self.tool_calls_used, self.max_tool_calls),
            (self.retries_used, self.max_run_retries),
        )
        for used, maximum in usage:
            if type(used) is not int or used < 0:
                raise OutputRejected("누적 사용량은 0 이상의 정수여야 한다")
            if maximum is not None and used > maximum:
                raise BudgetExceeded

    def limit_output_tokens(self, requested: int) -> int:
        """요청·호출당 서버 상한·남은 누적 출력량 중 가장 작은 양을 반환한다.

        입력 토큰은 공급자 응답 전 정확히 알 수 없으므로, 보고된 입력 사용량이
        한도에 도달하면 후속 호출을 차단한다. 초과 응답은 record_tokens가 거부한다.
        """
        self.check_deadline()
        self.check_limits()
        if type(requested) is not int or requested <= 0:
            raise OutputRejected("모델 출력 한도는 양의 정수여야 한다")
        if self.max_input_tokens is not None and self.input_tokens_used >= self.max_input_tokens:
            raise BudgetExceeded
        limited = min(requested, self.max_output_tokens)
        if self.max_total_output_tokens is not None:
            remaining = self.max_total_output_tokens - self.output_tokens_used
            if remaining <= 0:
                raise BudgetExceeded
            limited = min(limited, remaining)
        return limited

    def record_tokens(self, *, input_tokens: int, output_tokens: int) -> None:
        """보고된 토큰을 누적하고 초과분까지 기록한 뒤 결과 적용을 차단한다.

        두 값을 먼저 검사하므로 잘못된 자료형이면 카운터를 부분 변경하지 않는다.
        실패한 원격 호출에서 보고하지 않은 과금 사용량을 0으로 확정하지는 않는다.
        """
        if any(type(value) is not int or value < 0 for value in (input_tokens, output_tokens)):
            raise OutputRejected("모델 사용량은 0 이상의 정수여야 한다")
        self.input_tokens_used += input_tokens
        self.output_tokens_used += output_tokens
        self.check_limits()

    def start_tool_call(self) -> None:
        """도구 호출 1회를 예약한다. 거부된 외부 호출을 자동 재시도하지 않는다."""
        self.check_deadline()
        self.check_limits()
        if self.max_tool_calls is not None and self.tool_calls_used >= self.max_tool_calls:
            raise BudgetExceeded
        self.tool_calls_used += 1

    def start_tool_retry(self) -> None:
        """업무 도구 재시도도 모델과 같은 실행 전체 재시도 예산을 소비한다."""
        self.check_deadline()
        self.check_limits()
        if self.max_run_retries is not None and self.retries_used >= self.max_run_retries:
            raise BudgetExceeded
        self.retries_used += 1

    def start_llm_call(self, *, is_retry: bool = False) -> float:
        """모델 호출 1회를 예약하고 이번 호출에 쓸 제한 시간(초)을 돌려준다.

        재시도는 총 시도 수와 총 재시도 수 모두에 포함한다. 마감이 지났거나
        상한을 다 썼으면 어떤 카운터도 증가시키지 않고 예외를 낸다.
        """
        self.check_deadline()
        self.check_limits()
        if type(is_retry) is not bool:
            raise OutputRejected("재시도 여부는 boolean이어야 한다")
        if self.llm_calls_used >= self.max_llm_calls:
            raise LlmCallLimitExceeded(f"허용 {self.max_llm_calls}회를 모두 사용했다")
        if is_retry and self.max_run_retries is not None:
            if self.retries_used >= self.max_run_retries:
                raise BudgetExceeded
        self.llm_calls_used += 1
        if is_retry:
            self.retries_used += 1
        return min(self.llm_call_timeout, self.remaining_seconds())
